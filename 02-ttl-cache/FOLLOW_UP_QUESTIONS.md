# Follow-Up Questions — TTL-based Cache Library

25+ realistic interviewer follow-ups, categorized, each with a full answer specific to
this design (no generic filler, no repeated questions across categories).

---

## Object-Oriented Design

**1. Why is `EvictionPolicy<K>` parameterized only on `K`, not `<K, V>`?**
Because an eviction policy's job — "which key should be evicted next" — never requires
knowing or touching the associated value. Narrowing the generic parameter is a direct
application of Interface Segregation: it means a policy implementation (and any test of
one) can never accidentally couple to value semantics, and it makes `LruEvictionPolicy`/
`LfuEvictionPolicy` trivially reusable even in a hypothetical key-only context (e.g. an
LRU *set*, not just a cache).

**2. Why does `CacheEntry` live in the same package as `TtlCache`, not as a public
type?**
It's an implementation detail of how `TtlCache` stores values internally — callers only
ever interact with raw `V` values via `Optional<V>`/`V` return types, never with
`CacheEntry` itself. Making it package-private (no modifier) keeps the public API surface
exactly as narrow as the `Cache<K,V>` interface, and leaves room to change `CacheEntry`'s
internal representation later (e.g. adding a "last accessed" timestamp for some future
feature) without it being a breaking API change for anyone outside the package.

**3. Why does `Cache<K,V>` extend `AutoCloseable` rather than just having a `close()`
method?**
So that `Cache` instances can be used in a try-with-resources block
(`try (Cache<K,V> cache = builder.build()) { ... }`), which is the idiomatic Java pattern
for "this object owns a resource (here: the reaper thread) that must be released" — it
also communicates the lifecycle contract directly in the type signature rather than only
in documentation.

**4. `TtlCache` is `final`. Why not leave it open for subclassing (e.g. to override
`enforceCapacity`)?**
Because this design's thread-safety guarantees are a property of the *whole* class
working together consistently — a subclass overriding one method (say,
`enforceCapacity`) could easily violate an invariant another method depends on (e.g.
calling the eviction policy out of order relative to the map), silently breaking the
concurrency guarantees documented in README §11 without the compiler catching anything.
Composition (supplying a different `EvictionPolicy`) is the sanctioned extension point;
inheritance was deliberately closed off ("favor composition over inheritance," applied
literally here) since there was no genuine "is-a" relationship requiring a subtype.

---

## SOLID Principles

**5. Where does this design apply the Open/Closed Principle most clearly?**
Adding a new eviction algorithm (say, a "clock"/second-chance policy) requires writing a
new class implementing `EvictionPolicy<K>` and zero changes to `TtlCache`,
`CacheBuilder`, or any existing policy — `TtlCache` is closed for modification but the
system as a whole is open for extension via the Strategy interface.

**6. Where might this design appear to violate Single Responsibility, and is that a
real violation?**
`TtlCache` does several things: stores entries, enforces TTL, enforces size, dispatches
listeners, records stats, and runs a reaper. At first glance that looks like several
responsibilities bundled into one class. The counter-argument: all of these are facets
of one cohesive responsibility — "be a cache" — and none of them is independently
reusable or independently varies without the others (you can't meaningfully have a
"TTL-enforcer" or a "stats-recorder" without the context of the cache they're attached
to). The genuinely independent axis — *which* eviction algorithm — is exactly the one
factored out as `EvictionPolicy`. This is a judgment call explicitly defended in the
README (§4): SOLID applied meaningfully, not mechanically split into more classes than
the actual variation points justify.

**7. Does `CacheBuilder` violate the Interface Segregation Principle by exposing seven
different configuration methods?**
No — ISP is about not forcing a *client* to depend on methods it doesn't use; a builder's
fluent setters are each independently optional and a caller only calls the ones relevant
to their configuration (`CacheBuilder.newBuilder().maximumSize(100).build()` never
touches the other five setters). ISP would be a real concern if `Cache<K,V>` itself (the
consumed interface) forced every implementer to support every feature regardless of need
— which it does not, since there's exactly one implementation here, and the interface is
already minimal.

**8. Which dependency is inverted, and between which two components?**
`TtlCache` depends on the `EvictionPolicy<K>` *interface*, not on `LruEvictionPolicy` or
`LfuEvictionPolicy` directly — the high-level policy (how the cache enforces capacity)
doesn't depend on low-level detail (which specific algorithm), both depend on the
abstraction. The same applies to `Clock` (production code depends on the interface; the
system clock and the test fake are both low-level details plugged in from outside).

---

## Design Patterns

**9. Why not use the Decorator pattern for TTL itself — e.g. a `TtlDecoratingCache`
wrapping a plain non-TTL cache?**
This was considered. The reason it was rejected: TTL isn't a cross-cutting concern that
can be layered transparently on top of an arbitrary cache — it needs to be checked at
the exact moment of `get()`/`compute()`, atomically with the read, to avoid the race
described in README §11.2. A decorator sitting *outside* the backing map would need to
do its own separate map lookup to check expiry, reintroducing a check-then-act gap the
current design specifically avoids by making the expiry check part of the same map read
that retrieves the value. TTL is treated as core to this cache's identity, not an
add-on.

**10. Would the Template Method pattern have been a reasonable alternative to Strategy
for eviction policies?**
Template Method would mean `TtlCache` (or an abstract base class) defines the skeleton of
"decide + evict" and subclasses override a single `chooseVictim()` step — but that
requires `TtlCache` itself to be subclassed per eviction algorithm (`LruTtlCache extends
TtlCache`, `LfuTtlCache extends TtlCache`), which couples the eviction algorithm choice
to the cache's *type* rather than its *configuration*. That would make it impossible to
pick the eviction policy at runtime via a builder parameter (as required) without an
`if/else` choosing which subclass to instantiate — strictly worse than Strategy's
runtime-pluggable composition for this requirement.

**11. The Null Object pattern is often used to avoid `null` checks — why does this
design use `Optional<V>` for `get()` instead?**
A Null Object for "absent value" would need a sentinel `V` instance meaningful across
*every* possible value type `V` the cache is parameterized with — impossible in general
for a generic type (there's no universal "null-object `V`" the cache could construct
without knowing anything about `V`). `Optional<V>` is the standard, idiomatic Java
mechanism for "a `V` or explicitly nothing" exactly when `V` is generic and unconstrained,
which is why it was chosen over both raw `null` returns (ambiguous and error-prone at
call sites) and a Null Object (not generically constructible here).

**12. Is the `Clock` interface an application of Dependency Injection, Strategy, or
something else?**
Primarily Dependency Injection (of a collaborator needed for testability) implemented
*through* the Strategy pattern's mechanics (an interface with swappable implementations)
— the two aren't mutually exclusive; "inject a strategy" is a completely standard
combination. The specific motivation here is narrower than general Strategy (there's
only ever one implementation in production, `Clock.systemClock()`) — its entire purpose
is making time a controllable, injectable dependency for deterministic tests
(`FakeClock`), which is arguably closer to a "seam for testability" than a true
multi-algorithm Strategy.

---

## Data Structures

**13. Why is `TreeMap` used for LFU's frequency buckets instead of a plain `HashMap<Integer,
LinkedHashSet<K>>` with a separately tracked minimum frequency?**
`TreeMap.firstEntry()` always gives the correct minimum-frequency bucket in O(log F)
without any separate bookkeeping to keep correct. A `HashMap` + manually-tracked
`minFrequency` int is marginally faster (true O(1)) but requires careful, easy-to-get-wrong
logic for *when* `minFrequency` needs to be recomputed — specifically, the moment the
bucket at the current `minFrequency` becomes empty (an eviction or the key's own
first access bumping it to frequency 2), you must re-scan or re-derive the new minimum,
which is exactly the kind of subtle correctness trap `TreeMap` avoids entirely by
construction. README §16 names this trade-off explicitly.

**14. Could a `PriorityQueue` have been used instead of `LinkedHashMap` for LRU?**
A plain `PriorityQueue` doesn't support O(1) removal of an arbitrary (non-head) element,
which `onAccess`/`onRemove` need constantly (every access must relocate that specific
key, not just peek/poll the extreme) — removing an arbitrary element from a binary heap
is O(n) (linear scan to find it) or O(log n) at best with an auxiliary
element→index map to avoid the scan, which is strictly more complex than
`LinkedHashMap`'s native O(1) "touch an arbitrary key" support and offers no compensating
advantage for this use case.

**15. What is the time complexity of `evictionCandidate()` for each policy, and does it
change under concurrent contention?**
LRU: O(1) (`entrySet().stream().findFirst()` on a `LinkedHashMap` in access-order mode
examines only the first bucket-chain entry, since iteration order *is* access order).
LFU: O(1) amortized / O(log F) worst case (`TreeMap.firstEntry()`, F = distinct
frequencies in use). Neither changes asymptotically under contention — contention
affects *how long a thread waits for the policy's lock*, not the algorithmic complexity
of the work done once it holds the lock.

**16. Why `LongAdder` rather than `AtomicLong` for stats counters, given `AtomicLong` is
simpler and more familiar?**
`AtomicLong.incrementAndGet()` has every concurrent incrementing thread CAS-looping on
the exact same memory location/cache line — under high contention (many threads calling
`get()` simultaneously, each incrementing the same hit/miss counter), this becomes a
measurable bottleneck purely from cache-line ping-ponging between cores. `LongAdder`
internally stripes the counter value across multiple cells, so concurrent increments
usually land on different cells (different cache lines), trading a small, bounded extra
memory footprint and a costlier `sum()` (which must total all cells) for dramatically
better increment throughput under contention — exactly the right trade for a counter
incremented on every single operation but only summed occasionally.

---

## Java Internals

**17. `CacheEntry` is immutable and published only through `ConcurrentHashMap`. Explain
precisely which JLS/JMM guarantee makes this safe without any `volatile` fields on
`CacheEntry` itself.**
`ConcurrentHashMap`'s own internal synchronization establishes a happens-before edge
between a `put`/`compute` call that installs a value and any subsequent `get`/`compute`
call (by any thread) that observes it — this is part of `java.util.concurrent`'s
documented memory consistency guarantees (every `java.util.concurrent` collection's
actions prior to placing an object into the collection happen-before actions after
retrieving it). Combined with `CacheEntry`'s fields being `final` (guaranteeing the
object is fully, safely constructed before any reference to it escapes, per JLS 17.5's
final-field semantics), any thread that successfully retrieves a `CacheEntry` from the
map is guaranteed to see its `value`/`expireAtMillis` fields fully and correctly
initialized — no `volatile` needed on `CacheEntry`'s own fields, because the *map*, not
the fields, is doing the publishing.

**18. What specifically does `ScheduledExecutorService.scheduleAtFixedRate` guarantee
about overlapping executions, and why does that matter for the reaper?**
It guarantees executions of the *same* scheduled task never run concurrently with each
other — if one sweep takes longer than the configured interval, the next sweep simply
starts immediately after the previous one finishes rather than running in parallel with
it (subsequent executions are delayed, not skipped or run concurrently). This matters
because it means `reapExpiredEntries()` never needs its own synchronization to protect
against *itself* running on two threads at once — there is structurally only ever at
most one reaper sweep executing at any instant, which is why the reaper code has no
internal lock of its own beyond what it shares with ordinary callers (`evictionPolicy`'s
lock, the conditional map remove).

**19. Why is the reaper thread created as a daemon thread specifically?**
A daemon thread doesn't prevent JVM shutdown — if `close()` is never called (e.g. the
application simply exits), a non-daemon reaper thread would keep the JVM alive
indefinitely waiting for that thread to finish, which it never will on its own (it's an
infinite periodic task). Marking it daemon means an application that forgets to call
`close()` still shuts down cleanly; `close()`'s explicit `shutdownNow()` remains the
correct, preferred way to stop it promptly, but daemon status is a safety net against
forgetting.

**20. Does autoboxing matter anywhere in this design given `K`/`V` are generic (and
primitives can't be type parameters)?**
Yes, in `LruEvictionPolicy`'s `LinkedHashMap<K, Boolean>` — every `put(key, Boolean.TRUE)`
either reuses the cached `Boolean.TRUE` singleton (so no actual allocation; `Boolean`
autoboxing of a `true` literal always returns the shared instance per the JLS) or, if `K`
itself is a boxed primitive type used as a cache key (e.g. `Cache<Integer, V>`), every
distinct `int` key outside the `Integer` cache range (-128 to 127) autoboxes to a new
`Integer` object on each `put`/`get` call unless the caller already holds a boxed
reference — a minor, generally negligible allocation cost inherent to using boxed
primitive keys in any Java generic collection, not specific to this cache, but worth
being aware of if `K = Integer` and keys are large/high-churn.

---

## Multithreading

**21. Could a `StampedLock` have replaced `ReentrantLock` for the eviction-policy
locks, given policies have more reads (`evictionCandidate`) than writes?**
`StampedLock`'s optimistic-read mode would help only if `evictionCandidate()`'s read
could tolerate occasionally retrying after detecting a concurrent write — but
`evictionCandidate()` needs to actually traverse a mutable structure
(`LinkedHashMap`/`TreeMap`) to find the extreme element, and those traversals aren't
safely retry-able against concurrent structural mutation the way a simple field read is
(an in-progress structural mutation could leave the traversal looking at a transiently
inconsistent state, i.e. it isn't "read a couple of fields," it's "walk a data
structure"). `ReentrantLock`'s pessimistic exclusion is the safer, simpler choice here
given the actual critical sections are O(1)/O(log F) structural traversals/mutations,
not plain field reads — `StampedLock`'s main advantage (cheap optimistic reads) doesn't
cleanly apply to "traverse a mutable linked/tree structure."

**22. Why doesn't `put()` need to hold any lock across both the `store.put()` call and
the subsequent `evictionPolicy.onInsert()` call?**
Because no invariant in this design requires those two operations to appear atomic as a
pair to an outside observer — the worst consequence of another thread observing the map
update before the policy update is a momentarily stale eviction-ordering decision (as
documented in README §11), never an incorrect `get()` result (which depends only on the
map, never on the policy). Since the policy is consulted *only* for "which to evict"
(never for correctness of reads), the two updates genuinely don't need to be atomic
together — forcing them to be would require a lock spanning both the map and the policy,
reintroducing exactly the kind of coarse-grained contention point this design avoids.

**23. What would go wrong if `evictionLock` were replaced with a `synchronized` block
on the `TtlCache` instance itself (`synchronized (this) { ... }`)?**
Functionally, for `enforceCapacity()` alone, not much would change — but it would be a
latent foot-gun: `synchronized (this)` locks on an object any external caller could also
synchronize on (e.g. `synchronized (myCache) { ... }` in unrelated code, perhaps
mistakenly trying to "make a sequence of cache calls atomic"), creating an unintended
shared lock between this class's internals and arbitrary external code — a classic
"don't expose your lock object" anti-pattern. A private, dedicated `ReentrantLock` field
(as this design uses) can never be accidentally acquired by code outside the class,
which is why it's preferred over synchronizing on `this` (or on the class object) for
any lock meant to be a pure implementation detail.

---

## Database and Persistence

**24. If this cache were backed by a database instead of being pure in-memory, what
would the TTL expiry mechanism look like, and would the "conditional remove" race
still apply?**
A DB-backed version would typically store `(key, value, expires_at)` rows and either (a)
rely on the database's own TTL/expiry feature if available (e.g. Redis's native `EXPIRE`,
or a DB-level TTL index), or (b) run a periodic `DELETE FROM cache WHERE expires_at <
now()` sweep analogous to this design's reaper. The same race *does* still apply in
spirit: a `SELECT` that reads a row and separately decides to `DELETE` it based on
`expires_at` has the identical check-then-act gap as the in-memory version, and the fix
is structurally the same idea — a conditional delete (`DELETE ... WHERE key = ? AND
updated_at = ?` or an equivalent optimistic-concurrency version column) rather than an
unconditional one, so a concurrent update to the same row between the read and the
delete isn't silently clobbered. The concept (conditional removal keyed on "the exact
version I observed") transfers directly; only the mechanism (CAS vs. a WHERE-clause
version check) differs.

**25. Why would adding a persistence layer to this cache risk violating an invariant
that currently holds implicitly?**
Currently, `EvictionPolicy` bookkeeping (recency/frequency order) only ever needs to be
consistent with the in-memory map — both are constructed fresh together and never
diverge across a process boundary. A naive persistence layer that snapshots `store`
(key→value→expiry) but not the `EvictionPolicy`'s internal ordering structure would, on
restart, rebuild a cache whose eviction order bears no relationship to the actual
historical access pattern (every restored key would effectively look "equally old" to a
freshly-constructed `LruEvictionPolicy`) — silently degrading eviction quality
immediately after every restart, a subtle correctness-adjacent regression that's easy to
miss if persistence is bolted on without also persisting (or deliberately resetting and
documenting the reset of) policy state.

---

## Performance

**26. Under what access pattern does LFU meaningfully outperform LRU for this cache,
and under what pattern does it do meaningfully worse?**
LFU wins when there's a stable set of "genuinely hot" keys accessed very frequently,
interspersed with a stream of one-off/rarely-repeated keys — LRU would evict a hot key
the moment enough distinct one-off keys are touched in a row (pure recency has no memory
of *how often* something was used, only *how recently*), while LFU correctly keeps the
hot key since its frequency count protects it. LFU does meaningfully worse when access
patterns shift over time (yesterday's hot key is cold today) — LFU's frequency count is
sticky and doesn't decay, so a key that was extremely hot once can occupy a
high-frequency bucket indefinitely, resisting eviction long after it's actually gone
cold, a problem LRU doesn't have (recency naturally "forgets" with every new access to
anything else). Production LFU implementations typically add frequency decay or aging
specifically to address this; this implementation does not (flagged as a known
simplification, not an oversight).

**27. The reaper sweeps the entire map every interval. For a cache with `maximumSize`
= 100,000 and a 1-second `reaperInterval`, what's a reasonable way to estimate whether
this is a problem before it shows up in production?**
Back-of-envelope: iterating 100,000 `ConcurrentHashMap` entries and checking an
`isExpiredAt` boolean comparison per entry is on the order of low single-digit
milliseconds on typical hardware (a simple in-memory traversal, no I/O, no allocation
per entry) — at a 1-second interval, that's well under 1% of a single core's time, almost
certainly not a problem at this specific scale. The right way to know for sure rather
than guess: a microbenchmark (JMH) of `reapExpiredEntries()` alone at the target
`maximumSize`, and/or production profiling (sampling profiler attached, looking for the
reaper thread's CPU share) rather than reasoning from first principles alone once the
system is live — the estimate above is a sanity check for "is this worth measuring at
all," not a substitute for measuring.

---

## Failure Handling

**28. If `loader.apply(k)` inside `computeIfAbsent` throws, does the eviction policy or
stats get into an inconsistent state?**
No — the exception propagates out of the remapping function passed to
`store.compute()`, and `ConcurrentHashMap.compute()`'s documented behavior is that if the
remapping function throws, the mapping is left unchanged (as if the function was never
called) and the exception propagates to the caller of `compute()`. Since
`loadedFreshValue.set(true)` is only reached *after* `loader.apply(k)` returns
successfully, a thrown exception means `loadedFreshValue` stays `false`, and the
`computeIfAbsent` code that would call `evictionPolicy.onInsert`/`stats.recordMiss`/
`enforceCapacity` never runs — no inconsistent partial state is created in either the
map, the policy, or stats.

**29. What happens to in-flight `get()` calls on other keys if the reaper thread
throws an uncaught exception during a sweep?**
Nothing — `get()`/`put()`/etc. never depend on the reaper thread being alive or healthy;
they operate directly on `ConcurrentHashMap` and the policy, entirely independent of
whether the reaper's scheduled task is currently running, has thrown, or has been
silently suppressed by `ScheduledExecutorService` (which stops scheduling further
executions of a task after an uncaught exception, per its documented behavior). The
*consequence* of a dead reaper is purely that active expiry stops happening (lazy expiry
on `get()` still works perfectly, since it doesn't depend on the reaper at all) — a
degraded-but-correct state, not a cascading failure, which is exactly why
`expireIfStillPresent`/`reapExpiredEntries` are kept deliberately free of anything that
could throw beyond the already-guarded listener dispatch (§13).

---

## Production Architecture

**30. In a multi-instance deployment (N service replicas, each with its own in-process
cache), how do you reason about cache consistency across replicas, given this design
has no cross-instance awareness at all?**
This design is explicitly single-process (README §1) — each replica's `TtlCache` is
completely independent, with no shared state or coordination. The practical consequence:
two replicas can simultaneously hold different (stale vs. fresh) values for the same
logical key, bounded only by each replica's own TTL — this is the standard, accepted
behavior of independent local caches (comparable to how independent DNS resolver caches
across machines aren't expected to be instantaneously consistent either), and is
explicitly *not* a bug to fix within this design's scope. If stronger cross-replica
consistency is required, the answer is architectural (the L1/L2 split with pub-sub
invalidation discussed in README §14/§17), not a change to `TtlCache` itself — `TtlCache`
remains the correct, reusable L1 building block either way.
