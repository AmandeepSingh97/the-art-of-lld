# Staff-Level Deep Dive — TTL-based Cache Library

Architectural decisions hard to reverse, scaling bottlenecks, failure modes, consistency
guarantees, concurrency correctness, extensibility, API evolution, and operational
complexity — in question-and-detailed-answer form. Every question is specific to this
cache design, not a generic "tell me about CAP theorem" prompt.

---

### 1. You use `ConcurrentHashMap#compute` to serialize loads for the same key in `computeIfAbsent`. What happens if the loader function itself is slow (e.g. a 2-second network call) — what's the actual blast radius?

`ConcurrentHashMap`'s per-key atomicity for `compute` means every other thread calling
`compute`/`put`/`get`-via-`compute` **for that same key** blocks for the duration of the
remapping function (in most CHM implementations this is a bin-level lock held across the
user function call — that's explicitly what makes the atomicity guarantee possible).
So: 64 threads all wanting "hot-key" all genuinely wait up to 2 seconds, serialized
behind the one doing the load — but critically, every thread wanting any *other* key is
completely unaffected, since CHM's locking is scoped to the hash bin, not the whole map.
The blast radius is "all concurrent callers of this one key," not "the whole cache." If
that's unacceptable (e.g. a slow loader could starve a hot key's callers for seconds),
the fix is not to abandon `compute` — it's to wrap the loader call with a timeout and a
circuit breaker *outside* the cache (as noted in README §17), so a slow backend degrades
to "fail fast and don't cache" rather than "hold every caller of this key hostage."

### 2. Your capacity-enforcement lock uses `tryLock()`, meaning the cache can transiently exceed `maximumSize`. Prove to me this can't blow up unboundedly under sustained write pressure.

The overshoot is bounded by the number of threads that *simultaneously* observe
`mappingCount() > maximumSize` while another thread already holds `evictionLock` — not
by time or by sustained pressure. Once any thread acquires the lock, its `while
(store.mappingCount() > maximumSize)` loop runs until the cache is back at or under the
cap, re-reading `mappingCount()` on every iteration (not a stale snapshot). So even under
sustained concurrent writes, at any given moment there is at most one active eviction
loop, and it does not exit until capacity is restored to the instant it checks — the
overshoot at any instant is bounded by (threads currently inside `put`/`computeIfAbsent`
between their `store.put`/`compute` call and their `tryLock` attempt), which in practice
is bounded by thread-pool size, not by elapsed time or total throughput. It is a
*spatial* bound (proportional to concurrency level), not a *temporal* one that could grow
without limit.

### 3. Two threads call `invalidate(key)` concurrently for the same key. Walk through exactly what happens, including whether the listener fires once or twice.

Both call `store.remove(key)` (the unconditional, two-arg-free form, since explicit
invalidation doesn't need the conditional CAS pattern — there's no "expected prior value"
to protect against, the caller just wants the key gone regardless of what's there).
`ConcurrentHashMap.remove(key)` returns the removed value to exactly one caller and
`null` to the other (whichever loses the race to the bin's internal lock) — this is a
guarantee of `ConcurrentHashMap`, not something this code has to engineer itself. The
`TtlCache.invalidate` method only calls `evictionPolicy.onRemove`/`notifyListeners` in
the branch where `removed != null`, so exactly one of the two threads fires the listener
and updates the policy; the other sees `null` and does nothing further. The listener
fires exactly once.

### 4. If a value's `equals`/`hashCode` is expensive or broken (e.g. mutable key with a `hashCode` that changes after insertion), what breaks?

Keys, not values, are hashed by `ConcurrentHashMap`. If a key's `hashCode()` changes
after insertion (a classic "mutable key in a hash map" bug, not specific to this cache),
the entry becomes unreachable via `get(mutatedKey)` — it's now filed under a hash bucket
that no longer matches what a lookup with the current state of the key would compute,
and from the map's internal perspective it's "lost" even though it's technically still
occupying memory in the old bucket, until something iterates the whole map (like the
reaper's sweep — which *would* eventually find and could expire it, since the reaper
iterates `entrySet()` directly rather than hashing). This is exactly why the README (§4)
states keys "must have correct, stable `equals`/`hashCode`" as an explicit precondition —
it's not a cache-specific bug, but it interacts with this cache specifically in that a
"lost" entry still occupies a capacity slot and is only recoverable by the reaper's
O(n) full-table scan, not by any `get`.

### 5. Your `LruEvictionPolicy` and `LfuEvictionPolicy` each hold their own internal lock, separate from `evictionLock`. Could two different policy instances ever deadlock against each other?

No — each `TtlCache` instance owns exactly one `EvictionPolicy` instance, and no code
path ever holds two different policy locks simultaneously (there's no operation that
touches two caches' policies at once in this design). Within a single policy instance,
every public method (`onInsert`/`onAccess`/`onRemove`/`evictionCandidate`/`clear`)
acquires the lock, does O(1)/O(log F) work, and releases it before returning — no nested
lock acquisition, no call-out to any other lock while holding it. The only way a deadlock
could be introduced is if a future change made a policy call another component that
itself tries to re-enter the same policy's lock (true reentrancy is fine, since
`ReentrantLock` supports it from the same thread — but *cross-thread* circular waiting
would require two threads each holding a lock the other wants, which the current
call graph simply never constructs).

### 6. You chose a conditional `store.remove(key, expectedEntry)` for expiry. Why not instead protect the whole expiry check-and-remove with `evictionLock` (the same lock used for capacity)?

Because that would turn `evictionLock` into a point of contention on the `get()` hot
path — every single `get()` call on an expired entry would need to acquire a shared lock,
serializing all lazy-expiry removals across *every* key in the cache behind one lock,
even though expiry of key A has nothing to do with expiry of key B. The conditional CAS
(`remove(key, expectedEntry)`) achieves the same correctness (no double-removal, no
removing a replaced value) with zero additional locking — it's a primitive
`ConcurrentHashMap` already provides, scoped automatically to just that key's bin. Using
a coarser lock here would be solving a per-key problem with a cache-wide tool, which is
exactly the kind of unnecessary serialization this design works hard to avoid elsewhere.

### 7. Suppose a new requirement arrives: "support a per-cache maximum total byte size (weight-based capacity), not just entry count, without changing existing call sites." How much of the existing design has to change?

Less than it might seem, which is a direct test of whether `EvictionPolicy` was designed
with the right seam. `maximumSize`/`mappingCount()` in `enforceCapacity()` would become
`maximumWeight`/a running weight total; the cleanest path is to introduce a
`ToLongFunction<V> weigher` supplied via the builder (defaulting to "weight 1 per entry,"
i.e. today's behavior, for full backward compatibility), and track a running weight
counter (another `LongAdder`, or a value maintained alongside the map) rather than
relying on `store.mappingCount()`. `EvictionPolicy<K>` itself needs **zero** changes — it
already only ever answers "which key to evict next," independent of how "over capacity"
is defined; the weight bookkeeping lives entirely in `TtlCache.enforceCapacity()`. This
is a direct payoff of the Interface Segregation decision in §4 (policies know nothing
about values) — if policies had been designed to also size/weigh values, this change
would have required touching every `EvictionPolicy` implementation.

### 8. Now suppose the requirement is "support dynamically changing `maximumSize` on a live cache, without rebuilding it." What's hard about this specifically (not generically)?

The hard part is not the simple case (raising `maximumSize` — trivially safe, nothing
needs eviction). The hard part is *lowering* it on a live cache under concurrent traffic:
you need `enforceCapacity()` to run against a new, smaller target while `put()`/
`computeIfAbsent()` calls are still arriving and potentially growing the map
concurrently. The existing `tryLock()`-based enforcement loop would actually handle this
correctly as-is (it already rechecks `mappingCount()` against `maximumSize` on every
iteration) *if* `maximumSize` became a mutable, volatile-read field instead of a `final`
one — the loop condition would simply start evaluating against the new, smaller number.
The real design question is API shape: does `setMaximumSize(long)` need to **block**
until the cache has actually shrunk down to the new size (synchronous, strong guarantee,
but could take arbitrarily long under write pressure racing the shrink), or is it
acceptable for it to be "eventually enforced" (asynchronous, matches this cache's
existing "soft target" philosophy for `maximumSize`)? Given the existing design already
treats `maximumSize` as a soft bound (§11.4), the natural, consistent answer is
asynchronous/eventual — but that's a deliberate API design choice to surface explicitly
to whoever asks for this feature, not something to decide silently.

### 9. At 10 million distinct keys with a modest average value size, what's the first thing that breaks, and how would you know?

The first thing to break is almost certainly **JVM heap pressure**, not any concurrency
mechanism — `ConcurrentHashMap` and `LinkedHashMap`/`TreeMap`-based policies all scale
structurally to millions of entries without an architectural ceiling, but every entry
costs real heap (the `CacheEntry` wrapper, map node overhead, `LinkedHashMap`'s intrusive
list node overhead, the value itself). You'd know via heap profiling / GC pause time
climbing (large old-gen occupancy from a 10M-entry cache triggers longer, more frequent
full GCs) before you'd see any lock-contention symptom. The actual *architectural*
answer, once you've confirmed it's heap, not contention: this is exactly the point where
"in-process cache" stops being the right tool, and the system should move toward the L1
(bounded, small, in-process)/L2 (Redis, off-heap, horizontally scalable) split discussed
in README §14/§17 — 10M entries in a single process's heap is a sign the cache has grown
beyond what "a library inside one process" should be solving.

### 10. What is the single first point of failure in this design if it were deployed as-is in a real service?

The background reaper thread's sweep cost: `reapExpiredEntries()` iterates the *entire*
map every `reaperInterval`, regardless of how many entries are actually expired. At
scale (millions of entries, short `reaperInterval`), this single scheduled task becomes
an O(n)-per-interval CPU cost that competes with application threads for CPU time, and
because it's a single-threaded `ScheduledExecutorService`, if one sweep takes longer than
`reaperInterval` (e.g. under GC pause or CPU starvation), subsequent sweeps queue up
rather than running concurrently — `scheduleAtFixedRate` does not let the same task
overlap itself, so sweeps can fall behind without ever catching up if they genuinely
can't keep pace with map growth. This is the single component most likely to need
attention first in production, specifically via the time-ordered structure mentioned in
README §14 (so sweeps cost proportional to *expired* entries, not total entries).

### 11. Could this cache ever return a value for a key that was already explicitly `invalidate()`-d by another thread, due to a race?

No — walk the two interleavings. (a) `invalidate` wins the race first: `store.remove(key)`
succeeds, so any subsequent `get()` sees `entry == null` and returns empty — there is no
stale entry left to read. (b) `get` wins first (reads the live entry before `invalidate`
runs): the `get()` call returns the value that was live *at that instant*, which is
correct — `invalidate()` running a moment later doesn't retroactively invalidate a read
that already completed; this is the same "linearizable per operation, not per logical
time window" guarantee any concurrent data structure provides, and is the expected,
correct behavior (a `get` that started before an `invalidate` and completed before it is
entitled to see the old value). What the cache does *guarantee* is that no `get()` call
that starts *after* a successfully-completed `invalidate()` call can observe the
invalidated value for that key, because `ConcurrentHashMap`'s own happens-before
semantics guarantee that a subsequent `get()` sees the result of the prior `remove()`.

### 12. Your design never mutates a `CacheEntry` in place — every update is a full replacement. What's the throughput cost of this versus an in-place mutable entry with a `volatile expireAt` field, under a workload that frequently refreshes the same key's TTL without changing its value?

A "refresh TTL only" operation under this design requires constructing a new
`CacheEntry` object (same value, new expiry) and a full `store.put`, i.e. it pays for a
new heap allocation and a full map write, even though conceptually only one `long` field
changed. A mutable-entry design with a `volatile expireAtMillis` field could do this with
no allocation and, if careful, no map write at all (mutate the field in place since the
map still points at the same entry object) — strictly cheaper for this specific
"TTL-refresh-only" pattern. This is a genuine, deliberate trade-off this design accepts:
it is not the lowest-possible-cost design for that one specific operation; it is the
design that makes the *general* concurrent-read-safety story (§9's immutability
rationale) trivially correct everywhere, including the expiry race in §11.2. If profiling
showed TTL-refresh-without-value-change was a dominant, hot operation, the fix would be a
dedicated `touch(key, newTtl)` method using a mutable, `volatile`-field entry *specifically
for that path*, while keeping the immutable-entry model for `put`/value-changing
operations — a hybrid, not a wholesale redesign, since the immutability property is
still worth keeping everywhere a value actually changes.

### 13. What happens if the process crashes mid-`enforceCapacity()`, with `evictionLock` held and three of five over-capacity entries already evicted?

There is no recovery concern here, because there is no persisted state to recover — the
entire cache, lock included, lives in process memory and ceases to exist the instant the
process does. On restart, a brand-new `TtlCache` is constructed from scratch (an empty
map, a freshly-constructed, unlocked `evictionLock`). This question would only have teeth
for a design with persistence (§12) — and it's precisely *why* this design explicitly
chooses not to persist eviction-in-progress state: a durable cache would need to treat a
partially-completed eviction pass as a recoverable, idempotent operation (e.g. logging
"intend to evict keys X, Y, Z" before acting, so a crash mid-way can be resumed or rolled
back cleanly) — meaningfully more complexity that only pays for itself if restart-survivability
is an actual requirement, which §1 explicitly scoped out.

### 14. A new requirement: "notify listeners *before* an entry is removed, so they can veto the removal." How invasive is this change?

Fairly invasive, and worth pushing back on in an interview rather than silently
accepting. The current `CacheEvictionListener.onEviction` is called *after* the removal
has already happened (both from the map and from the policy's bookkeeping) — it's a
pure notification, with no path back into the removal decision. Supporting a veto
requires restructuring every removal path (`expireIfStillPresent`, `enforceCapacity`,
`invalidate`) to call listeners *before* mutating `store`, inspect a boolean/exception
return to decide whether to proceed, and only then do the conditional
remove/policy-update/stats-update — and now you must decide what happens if two
listeners disagree, what happens if a veto races with another thread's own removal
attempt for the same key, and whether a vetoed TTL expiry effectively extends the TTL (and
by how long, to avoid the reaper immediately re-triggering the same veto in a tight
loop). This is a legitimate ask but is a different, harder contract than "observe
removals" — it's worth explicitly naming the complexity increase rather than bolting it
on casually, and a Staff-level answer should flag that the simplest honest response is
"this needs a different interface (`CacheRemovalVote` or similar), not an overload of the
existing Observer-style listener."

### 15. How would you change the design if you needed strict (not "soft target") enforcement of `maximumSize`, with zero tolerance for transient overshoot?

Replace `evictionLock.tryLock()` with `evictionLock.lock()` (blocking) in
`enforceCapacity()`, and additionally make `enforceCapacity()` run *before* admitting a
new entry rather than after, i.e. evict proactively down to `maximumSize - 1` before
`store.put()` for a brand-new key, rather than admitting-then-evicting. This closes the
overshoot window entirely but reintroduces the exact serialization cost this design
deliberately avoided: every concurrent `put()` of a new key under capacity pressure now
queues behind a single lock, turning writes into a point of contention proportional to
insert rate rather than proportional to actual eviction frequency. This is a legitimate,
named trade-off to make explicitly if the requirement genuinely demands it (e.g. hard
memory ceiling where even one entry of overshoot risks OOM) — but it should be presented
as a conscious regression in write throughput, not a free change.

### 16. Is `TtlCache.size()` linearizable — i.e. if I call `put` then immediately call `size()` from the same thread, is the new entry guaranteed counted?

Yes, for that specific same-thread sequential case: `store.mappingCount()` reads
`ConcurrentHashMap`'s internal counters, and the thread that just completed its own
`store.put()` call has a program-order (and therefore happens-before, within one thread)
relationship with its own subsequent `size()` call — there's no reordering concern within
a single thread's own sequence of operations on the same object. What is *not* guaranteed
is a cross-thread snapshot property: if thread A calls `put` and, concurrently, thread B
calls `size()`, B may or may not observe A's new entry depending on exact timing — this
is the expected, standard semantics of `ConcurrentHashMap.mappingCount()` (itself not a
globally atomic snapshot across concurrent mutators), and is explicitly why `size()`'s
javadoc in `Cache.java` describes it as "the current number," not "a consistent
snapshot."

### 17. Your `CacheStatsRecorder` snapshot combines four independently-updated `LongAdder`s. Could `hitRate()` on a snapshot ever report a mathematically "impossible" combination, like a hit count that doesn't match what actually happened?

A `CacheStats` instance itself is always internally consistent *as a record* — once
`snapshot()` constructs it, the four `long` fields are frozen, immutable, and will never
change or desynchronize from each other after that point (that's what immutability
buys here). What's *not* guaranteed is that the snapshot corresponds to any single
globally-consistent instant across the whole cache: between reading `hits.sum()` and
`misses.sum()` inside `snapshot()`, more hits/misses can be recorded by other threads, so
the four numbers collectively describe "approximately now," not "exactly this instant" —
e.g. `hitCount + missCount` might be slightly less than the true total requests that have
occurred by the time you read the snapshot, if concurrent `get()` calls completed their
operation but not yet their stats increment. This is explicitly documented in
`CacheStatsRecorder`'s javadoc as an accepted trade-off: stats are an observability aid,
not a source of correctness truth, and paying for a globally consistent snapshot (which
would require pausing all counter updates, i.e. a lock on the hottest path in the whole
cache) is a wildly disproportionate cost for a debugging/metrics feature.

### 18. You rejected a hand-rolled intrusive doubly-linked list for LRU in favor of `LinkedHashMap`. If an interviewer insists "implement it manually, I want to see you reason about the linked-list pointers," what's the key correctness pitfall to call out explicitly?

The classic pitfall is forgetting to detach a node from *both* directions before
re-inserting it at the head on access — i.e. on `onAccess(key)`, you must update
`node.prev.next = node.next` and `node.next.prev = node.prev` (unlinking) *before*
relinking it at the head, and you must handle the node being the current head or tail as
special cases (null-checking `prev`/`next` rather than assuming both always exist) — a
very common bug is correctly handling the "middle of the list" case and then crashing or
silently corrupting the list on the eviction candidate (the tail) specifically because
the tail-removal path wasn't special-cased. The second classic pitfall under
concurrency specifically: every pointer update (unlink + relink) must happen under the
*same* critical section as a single atomic unit — doing "unlink" and "relink" as two
separately-locked operations reopens a window where another thread's concurrent
`evictionCandidate()` read could observe a half-updated list (a node in neither the old
nor new position, or a cycle). This is precisely why this design's actual
`LruEvictionPolicy` holds its lock for the entirety of each method rather than per
pointer update.

### 19. If you had exactly 2 weeks to ship a production version of this, what would you deliberately simplify from the README's "full" design, and why those specific cuts?

Cut, in priority order: (1) LFU — ship LRU-only; LFU's value (protecting genuinely
hot keys from a burst of one-off inserts) is real but secondary to "has a working,
correct cache at all," and it can be added later as a pure addition (new
`EvictionPolicy` implementation) without touching anything else — exactly the
extensibility property Strategy was chosen to provide. (2) The time-ordered reaper
optimization (§14) — ship the simple full-table-scan reaper; it's correct and
sufficient unless/until profiling shows it's a bottleneck at actual production scale,
and building the optimized version speculatively risks solving a problem that may never
materialize. (3) Weight-based capacity — ship count-based only; it's the simpler,
good-enough default for most workloads and the migration path (§7's answer) is clean
enough to defer. **Not cut, under any time pressure**: the conditional-remove expiry
race fix, the `compute`-based stampede protection, and the test suite proving both —
these are the parts where "looks right" and "is right" diverge sharply enough that
shipping the wrong version silently corrupts behavior under load rather than failing
loudly, which is a far worse 2-week-ship risk than a missing feature.

### 20. How would you extend this design to support "cache groups" — invalidating all keys matching a prefix or tag, e.g. `invalidateAll(tag: "user:42")`, without a full scan?

The current design has no secondary index beyond the key→entry map itself, so a
prefix/tag invalidation today requires an O(n) scan filtering by key pattern — acceptable
occasionally, poor if frequent. The clean extension is a secondary
`Map<Tag, Set<K>>` index, maintained alongside `store` the same way `EvictionPolicy` is
maintained alongside it today: on `put(key, value, ttl, tags)` (a new overload), add
`key` to each tag's key-set; on any removal path, remove `key` from its tags' sets (the
same discipline already applied to the eviction policy — "every removal path must
update every auxiliary structure"). The interesting design question is consistency: do
you accept the same "update the map first, auxiliary structure very slightly after"
window already accepted for `EvictionPolicy` (§ "Eviction-policy bookkeeping" in
README §11.8), or does cache-group invalidation have a stricter consistency requirement
(e.g. "once `invalidateAll(tag)` returns, no caller can observe a value under that tag,
even one concurrently being inserted under that same tag") that would need tighter
coordination — this is exactly the kind of requirement that should be clarified (does a
`put` racing an `invalidateAll` for the *same* tag need to lose, deterministically,
rather than being a toss-up) before committing to an implementation, since the answer
materially changes whether a simple best-effort index suffices or a per-tag lock is
needed.

### 21. Does this design have any hidden coupling between `TtlCache` and the specific choice of `ConcurrentHashMap`, such that swapping the backing store would be harder than it looks?

Yes, in one specific place: the conditional expiry removal (`store.remove(key,
expectedEntry)`, §11.2) and the atomic compute-based stampede protection (§11.3) both
rely on `ConcurrentHashMap`-specific methods (`remove(Object, Object)`,
`compute(Object, BiFunction)`) that are part of the broader `java.util.Map`/
`ConcurrentMap` interface contract, not accidental implementation details — so swapping
to, say, a different `ConcurrentMap` implementation (e.g. a custom segmented map) would
work as long as it honestly implements the `ConcurrentMap` interface's documented
atomicity guarantees for those two methods. The actual hidden coupling is subtler:
`store.mappingCount()` is a `ConcurrentHashMap`-specific method (not on the `Map`
interface at all — the generic equivalent would be the less-precise `size()`), chosen
deliberately because `ConcurrentHashMap.size()` can overflow/misbehave for very large
maps in a way `mappingCount()` (returning `long`) doesn't; swapping the backing store to
a plain `ConcurrentMap`-typed field would silently lose this precision benefit unless the
replacement also exposes an equivalent `long`-returning count. This is called out so that
"depend on the interface, not the implementation" isn't applied mechanically here without
noticing the one spot where the concrete type was chosen for a real, documented reason.
