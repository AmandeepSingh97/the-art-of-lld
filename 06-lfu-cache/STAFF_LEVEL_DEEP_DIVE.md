# Staff-Level Deep Dive — LFU Cache

This document pressure-tests the design in `README.md`. Each question is
specific to LFU's structure, not a generic "tell me about concurrency"
prompt — the reasoning matters more than the one-line verdict.

---

### 1. Two threads call `get` on the same key at the same moment, on a bare (unprotected) `LFUCache`. Walk through exactly how this corrupts state, step by step.

Say the key's node `N` currently sits in `FrequencyList[3]` with neighbors
`P` (prev) and `Q` (next). Both threads independently execute `touch(N)`:
both read `oldFrequency = 3`, both call `oldList.remove(N)`. If these
interleave at the pointer-write level, you can get: Thread A sets
`P.next = Q` (unlinking N). Thread B, having already cached `N.prev == P`
and `N.next == Q` *before* A's write, now also sets `P.next = Q` (no-op,
harmless) but then both threads proceed to `node.frequency = 4` — one of
them "wins" the final write, but **both** call
`frequencyToList.computeIfAbsent(4, ...).addToHead(N)`. If both executions
of `addToHead` interleave, you can end up with `N` linked into the
frequency-4 list *twice* conceptually (e.g. `head.next` gets set to N by
thread A, then thread B's `addToHead` also sets `head.next = N` after
making `N.prev = head`, but meanwhile A already made the *previous*
`head.next` point back to N as `N.next`'s predecessor) — producing a
list where `N.prev`/`N.next` are inconsistent with what `head`/the node
that used to be first actually point to. The concrete symptom varies by
exact interleaving, but the invariant that breaks is: "every node's
`prev`/`next` are each other's mutual neighbors" — once that's violated,
a subsequent `removeTail()` can throw an NPE (walking off a null `prev`)
or silently drop/duplicate entries, corrupting `size()`'s accuracy from
that point forward.

### 2. The JVM crashes (process killed) in the middle of `evictLeastFrequentlyUsed()`, after `keyToNode.remove()` but conceptually "before" the eviction listener's write-back completes. What state is lost, and does it matter?

Everything is lost — this is a pure in-memory cache, so a crash loses the
*entire* cache contents, not just the entry mid-eviction. This is by design
and documented in section 12 of `README.md`: a cache's contract never
promised durability, so this isn't a failure to recover from, it's
expected behavior. The more interesting sub-question is the listener's own
write-back: if the listener was mid-write to a backing store when the
process died, that write is now in an unknown state (maybe partially
applied) from the backing store's perspective — but that's a property of
whatever I/O the listener does, entirely outside `LFUCache`'s control or
responsibility. The honest answer at staff level is: "the cache itself has
no crash-recovery story and shouldn't need one; if write-back durability
matters, that's a transactional concern belonging to the listener/store,
with its own idempotency and retry design — layering that correctly is a
bigger conversation than this LLD."

### 3. A new requirement arrives: "support `remove(key)` to explicitly evict a key without it ever being a capacity-driven eviction." Can you add this without changing existing code?

Yes, and tracing *why* is a good test of whether the invariants in section
5 were actually understood, not just stated. `remove(key)` needs to: look
up the node via `keyToNode`, unlink it from its current `FrequencyList`
(exactly the first half of `touch`'s logic — remove from old list, delete
the bucket if now empty, bump `minFrequency` if that bucket was the
minimum), then remove it from `keyToNode`. This is a strict subset of
logic already proven correct in `touch`/`evictLeastFrequentlyUsed` — no
existing method needs modification, only a new method combining existing
primitives (`FrequencyList.remove`, the `minFrequency` bump condition).
This is a direct demonstration of Open/Closed holding in practice, not
just claimed: the invariants were designed narrowly enough (section 5)
that a new mutating operation composes from existing verified pieces.

### 4. Another new requirement: "I want to know, for any key, its *current* frequency, without it counting as an access." Does this break the O(1) guarantee, and does it interact badly with anything?

It's O(1) (`keyToNode.get(key)` then read `node.frequency`), and it
doesn't interact badly *if* implemented as a truly read-only peek — the
risk is a future maintainer "helpfully" routing it through `touch()` by
habit, silently turning an inspection API into a mutating one. This is
worth flagging explicitly in a review: name the method something like
`peekFrequency(key)` rather than `frequencyOf(key)`, to make its
non-mutating nature part of the name, not just a comment that can be
missed or go stale.

### 5. What's the first thing to break at 10 million distinct keys with a capacity of, say, 100,000?

Nothing breaks *structurally* — the design's complexity bounds don't
depend on the *total* key space, only on `capacity` (bounded map/list
sizes) — but two things become operationally sharp: (a) `HashMap` resize
events become more expensive in absolute terms as the maps grow toward
their steady-state size (still O(1) amortized, but the constant factor of
an occasional O(n) resize is now a real latency spike at `n` = 100,000,
not negligible); (b) if frequencies are unbounded (a single hot key
accessed millions of times), `node.frequency` as an `int` can in principle
approach overflow in extreme, long-running, very-hot-key scenarios —
practically distant (2^31 accesses) but worth naming as a boundary rather
than assuming "frequency never gets that big." Neither is a design flaw;
both are the kind of thing you'd catch via load testing and address with
either pre-sized maps or a frequency-capping/aging scheme (see question 9).

### 6. What's the first thing to break if `capacity` is set to something like 10 but the real workload has 10,000 "equally hot" keys cycling through?

This isn't a bug, it's thrashing — with `capacity=10` and 10,000 roughly
equally-frequent keys, the cache will evict and re-insert almost
constantly, and the hit rate will be close to zero because nothing stays
resident long enough to be re-hit before being evicted for the next key in
the cycle. The design handles this *correctly* (no corruption, no
violated invariants) but *uselessly* — this is exactly the kind of signal
`StatsRecordingCache.stats().hitRate()` is meant to surface in production
(section 17): a near-zero hit rate under load is a capacity-sizing
problem, not a code problem, and conflating the two is a common mistake
when a cache "isn't helping" in production.

### 7. Why is `LFUCache` declared `final`, and what would go wrong if someone removed that modifier to let a team "customize eviction behavior" via subclassing?

`final` is a deliberate encapsulation boundary, not an oversight. If a
subclass overrode `put` to "customize" eviction (say, to add a secondary
LRU-only fallback), it would almost certainly end up calling or
reimplementing logic that needs intimate knowledge of `minFrequency`'s
invariant (section 5) and the exact ordering of operations in `touch`
and `evictLeastFrequentlyUsed` — both currently `private`, meaning a
subclass literally cannot access them without the maintainers first
widening their visibility, which itself starts leaking internal state
(`Node`, `FrequencyList`) that the whole O(1) proof depends on staying
unreachable from outside. The correct extension points are already
external: `EvictionListener` for reacting to evictions, and composition
(`ConcurrentLFUCache`, `StatsRecordingCache`) for cross-cutting behavior.
If "customized eviction" genuinely means a different *policy* (not a
customization of LFU itself), that's a new class implementing `Cache<K,V>`
from scratch, not a subclass of this one.

### 8. The interviewer says: "Your `minFrequency` field — convince me it can never point at a frequency bucket that doesn't exist, under any sequence of operations." Prove it, not just assert it.

Induction on the sequence of operations. Base case: a fresh `LFUCache` has
`minFrequency = 0` and `frequencyToList` is empty — `get` on any key is a
miss (no-op on `minFrequency`), and `put` on a new key sets
`minFrequency = 1` *after* inserting into `frequencyToList.get(1)`, so the
bucket exists by the time `minFrequency` is set to point at it (also true
for every subsequent `put` of a *new* key — the `computeIfAbsent` call
precedes the `minFrequency = 1` assignment, textually and in execution
order). Inductive step: assume the invariant holds before a `touch(node)`
call (whether from `get` or an updating `put`). `touch` only modifies
`minFrequency` in one place: `if (oldFrequency == minFrequency) {
minFrequency++; }`, guarded by `oldList.isEmpty()` having just become true.
By the per-step argument in README section 10 ("Why the `minFrequency++`
step is correct"), the bucket at `oldFrequency + 1` is guaranteed non-empty
immediately after this `touch` call completes (the node being moved is
inserted there before the method returns) — so the new `minFrequency`
value always points at a bucket that, by the time any *other* code can
observe `minFrequency`, is populated. `evictLeastFrequentlyUsed` never
modifies `minFrequency` itself (only `put`'s insert path does, resetting
it to 1, which is always valid for the same reason as the base case).
Since every modification site maintains the invariant and no other code
path touches `minFrequency`, it holds for all reachable states.

### 9. A new requirement: "entries should also expire after N seconds regardless of frequency." How would you add TTL, and is it a clean extension or does it fight the existing design?

It's not a clean drop-in, and saying so honestly is the staff-level answer
over forcing it in. TTL is an orthogonal concern with its own natural data
structure (typically a min-heap or a time-bucketed wheel ordered by
expiry time) — bolting it onto the frequency-bucketed structure would mean
every node needs *two* independent positions to track (its frequency-bucket
position and its expiry-order position), and eviction now has two
*independent* triggers (capacity pressure vs. time) that can race with
each other (a background expiry sweep removing a node while a `put`
concurrently evicts it for capacity reasons). The honest design is a
composition, not a merge: wrap `Cache<K,V>` with a `TtlCache<K,V>` decorator
that checks/evicts expired entries lazily on access (or via a background
sweep) *before* delegating to the LFU cache, keeping the two concerns'
code — and more importantly their *locking* — separate. Forcing TTL logic
into `LFUCache` itself would be exactly the kind of hidden coupling a staff
reviewer should flag.

### 10. What's the actual, measurable cost of the single lock in `ConcurrentLFUCache` at high write concurrency — not "it's a bottleneck," but what number would you expect and how would you find it?

The cost is the sum of (a) the per-call lock acquire/release overhead
(roughly tens of nanoseconds uncontended with `ReentrantLock`, since Java
21's lock implementation is well-optimized for the uncontended fast path)
plus (b) the *actual* serialized work inside the critical section (a few
HashMap operations and pointer writes — typically tens to low hundreds of
nanoseconds) plus (c), the one that actually dominates under real
contention, context-switch/park overhead for threads that lose the race
for the lock and must wait. You'd measure this empirically, not guess it:
a JMH (Java Microbenchmark Harness) benchmark with varying thread counts
hammering a fixed-size cache, measuring throughput (ops/sec) as thread
count increases past the number of cores — the point where throughput
plateaus or declines is the empirical contention ceiling for a given
workload shape, and it is a legitimate "it depends on the workload" answer
staff engineers should be comfortable giving instead of a fabricated
number.

### 11. Could you make `get` lock-free while keeping `put` locked, as a hybrid? Would that actually help?

No, and the reason is specific to LFU, not a generic "reads and writes
both touch state" platitude: in a plain cache, `get` is a pure read and
could plausibly be made lock-free (e.g. via a `ConcurrentHashMap` and
accepting slightly stale reads). But in *this* design, `get` is never a
pure read — every successful `get` mutates the frequency bucket structure
(bumps frequency, migrates between lists, possibly bumps `minFrequency`).
There is no "read-only `get`" to optimize separately from `put`; the two
operations share the identical mutation path (`touch`). A hybrid
locking scheme here would need to protect `get` just as fully as `put`,
making the hybrid pointless — this is a genuinely distinguishing property
of frequency-based caching versus, say, a cache where `get` really is
read-only and only `put` needs protection.

### 12. If you had to ship a correct, interview-quality LFU cache in two weeks for an internal tool with at most a handful of callers, what would you deliberately simplify, and what would you refuse to cut?

Would simplify: skip `StatsRecordingCache`/`CacheFactory`/`EvictionListener`
entirely — ship bare `LFUCache` plus `ConcurrentLFUCache`, add
instrumentation only if/when someone actually asks "is this cache
helping." Would also skip pre-sizing the internal `HashMap`s and any
micro-optimization. Would refuse to cut: the correctness of the LFU+LRU-
tie-break eviction order itself (the entire point of choosing LFU over a
simpler `LinkedHashMap`-based LRU cache in the first place — if
correctness there is compromised, you should have just shipped
`LinkedHashMap` and saved the two weeks) and the explicit `final` + private-
nested-class encapsulation (removing that "to save time" is the kind of
shortcut that turns into an unmaintainable mess the moment a second
engineer touches the class, which is a near-certainty for anything
shipped to "a handful of callers" that outlives its original author).

### 13. Why does `put` on an existing key call `touch(existing)` *after* setting `existing.value`, rather than before? Does the order matter?

It doesn't matter for correctness here, because `touch` operates purely on
`node.frequency`/list-membership, never reads `node.value` — but it's
worth being able to say *why* it doesn't matter rather than just asserting
it. If a future change made `touch` (or anything in the eviction/notification
path) depend on the *value* (e.g. a listener wanting "old value" vs. "new
value" at eviction time), this ordering would suddenly matter and the
"doesn't matter" answer would need re-deriving, not merely re-asserting
from memory — a good staff-level habit is to notice when a "doesn't
matter" claim is contingent on current implementation details rather than
a structural property.

### 14. The API currently returns `Optional<V>` from `get`. A consumer team asks for a batch `getAll(Collection<K> keys)` returning `Map<K, V>`. Does this compose cleanly, and what's the concurrency subtlety?

It composes at the single-threaded `LFUCache` level trivially (a loop
calling `get` per key, collecting present values into a `Map`). The
concurrency subtlety under `ConcurrentLFUCache`: a naive `getAll` that
calls the already-locked `get` once per key acquires and releases the lock
*N* times for *N* keys, which is correct but not atomic as a whole batch —
another thread's `put` can interleave between two keys of the same
`getAll` call, so the returned map is not a consistent snapshot across all
keys, only key-by-key consistent. Whether that's acceptable depends on
the caller's actual need (usually it is, since a "snapshot" semantic for
an actively-mutating cache is already a soft concept) — but it needs to be
asked and stated, not silently assumed either way.

### 15. Someone proposes replacing `HashMap<Integer, FrequencyList<K,V>>` with a plain array indexed by frequency, since frequencies start at 1 and increase by 1 each time, arguing it'd be faster than hashing an `Integer`. Evaluate this.

The performance argument has some truth (array indexing avoids hashing and
autoboxing `Integer` keys, which do cost something), but it introduces a
real correctness/resource problem: frequencies are *unbounded* in
principle (a key accessed a million times has frequency 1,000,000), so an
array would need to either be pre-sized to some assumed maximum frequency
(an arbitrary, workload-dependent guess that risks either wasting memory
for a small cache or an `ArrayIndexOutOfBoundsException` for a hotter-
than-expected key) or dynamically resized (losing much of the simplicity
win over a `HashMap`, which already dynamically resizes). The `HashMap`'s
sparsity-handling is doing real work here: at any moment, the number of
*distinct active frequencies* is bounded by `capacity` (at most one
distinct frequency per entry), even though the *frequency values
themselves* are unbounded — a `HashMap` handles "few distinct keys from an
unbounded domain" exactly right, which a fixed-size array cannot.

### 16. What happens to the design if keys can be mutated after insertion (e.g. a mutable `K` whose `hashCode()` changes)?

This breaks the cache silently and badly, and it's worth being explicit
that this is a documented precondition violation, not a defect in
`LFUCache`: `keyToNode`'s internal bucket placement is computed from
`key.hashCode()` at insertion time; if the key object is later mutated
such that its hash code changes, `keyToNode.get(key)` (even with the exact
same object reference) can look in the *wrong* bucket and report the key
as absent, even though the entry is still physically present in the map's
internal array — a classic "broken hash contract" bug that is entirely the
caller's responsibility (the Javadoc for `Cache<K,V>` should, and does via
its generic parameter documentation convention, require well-behaved,
immutable-for-the-duration keys). This isn't LFU-specific — it's a general
`HashMap` hazard — but it's worth being ready to name precisely when asked,
rather than vaguely saying "keys should be immutable."

### 17. If `EvictionListener.onEvict` is slow (e.g. does a synchronous network call), what's the blast radius under `ConcurrentLFUCache`?

The blast radius is total serialization of *every* cache operation across
*every* thread, not just the one triggering the eviction — because
`onEvict` is invoked from inside `evictLeastFrequentlyUsed()`, which runs
while `ConcurrentLFUCache`'s single lock is held (per the class's own
documented contract that listeners must be fast). A slow listener turns
the cache's throughput ceiling into the listener's latency, defeating the
entire purpose of using a cache for low-latency access. The fix is never
"make the lock smarter" — it's "the listener must not do synchronous I/O
under the lock," e.g. by handing evicted entries to a bounded queue drained
by a separate thread. This is exactly the kind of blast-radius question
that separates "I added an extension point" from "I understood the
operational contract that extension point requires from its users."

### 18. Is `CacheStats` safe to read concurrently with ongoing `get`/`put` calls on `StatsRecordingCache`? What consistency guarantee does a caller actually get?

It's safe in the sense that no exception or corruption occurs (each
counter is an independent `AtomicLong`), but the three counters read by
`stats()` are **not** read atomically as a group — `hitCount`, `missCount`,
and `evictionCount` can each reflect a different "as of" moment relative
to concurrent activity, so a caller computing, say, "evictions as a
fraction of total puts" from two separate `stats()` calls (or even one
call racing with in-flight operations) gets an approximation, not an exact
snapshot. This is explicitly acceptable for *this* use case (observability
metrics tolerate eventual consistency routinely — see README section 11's
note on this being a deliberate trade-off) but would be the wrong answer
if `CacheStats` were ever repurposed for something requiring strict
consistency (e.g. a billing calculation based on access counts), which is
exactly the kind of hidden-coupling trap a staff reviewer should flag if
`CacheStats` use ever drifts toward that.

### 19. Why is `FrequencyList` a private *static* nested class rather than a non-static inner class of `LFUCache`?

A non-static inner class in Java implicitly holds a reference to its
enclosing `LFUCache` instance, which `FrequencyList` has no actual need
for — it only operates on `Node` objects passed to its methods, never on
`LFUCache`'s own fields. Making it `static` avoids that unnecessary
implicit reference (a small but real memory/GC consideration: a non-static
inner class instance keeps its enclosing instance reachable, which could
matter if `FrequencyList` objects ever outlived their `LFUCache`, though
they don't here) and, more importantly for interview purposes, makes
the dependency explicit and auditable: `FrequencyList`'s constructor and
methods only ever take what they need as parameters, which is exactly the
kind of minimal-coupling discipline worth calling out unprompted if asked
to review this code.

### 20. The interviewer asks: "What's the first thing you'd tell a new engineer joining this codebase to *never* do, specific to this class?" What's the sharpest, most specific answer?

"Never call any `FrequencyList` or `Node` method, or reach into
`LFUCache`'s `minFrequency`/`frequencyToList` fields, from anywhere outside
`LFUCache.touch()`/`evictLeastFrequentlyUsed()` — those two private methods
are the *only* places where the invariant 'minFrequency always points at a
non-empty, truly-minimal bucket' is proven to hold, and that proof is an
inductive argument over the *exact* sequence of operations in those two
methods (see `STAFF_LEVEL_DEEP_DIVE.md` Q8). Any new code path that
touches those structures directly — even with good intentions, like 'a
quick debug method to print all frequencies' — needs to independently
re-derive and preserve that invariant, which is exactly the kind of
easy-to-get-subtly-wrong work this encapsulation boundary exists to
prevent people from having to redo."

---

## Manual (non-CI) race-condition experiment

Referenced from `README.md` section 15: to *observe* (not formally prove)
an unsynchronized `LFUCache` corrupting its invariants under contention,
run many threads against a bare `LFUCache` (no `ConcurrentLFUCache` wrapper)
with a small, overlapping key space and assert `size() <= capacity()` after
every operation, same shape as
`ConcurrentLFUCacheTest.sizeNeverExceedsCapacityUnderConcurrentPuts`, but
pointed at a bare `LFUCache` instead. On most JVMs/hardware this will
eventually throw a `NullPointerException` from a corrupted linked-list
traversal, or report `size() > capacity()`, within a few runs — but
because the exact iteration count needed to trigger it depends on thread
scheduling, core count, and JIT warmup, it is deliberately **not** checked
into the automated test suite (see `README.md` section 15 for the full
rationale) and should only be run interactively, by hand, as a teaching
exercise.
