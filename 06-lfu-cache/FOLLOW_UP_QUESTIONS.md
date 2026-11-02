# Follow-Up Questions — LFU Cache

Organized by category, as realistically asked in a 30–60 minute LLD round.
Each has a complete answer, not a one-liner.

## Object-Oriented Design

**1. Why is `Cache<K,V>` an interface rather than an abstract class?**
There is no shared implementation state or template-method behavior to
reuse across `LFUCache`, `ConcurrentLFUCache`, and `StatsRecordingCache` —
each implements the four methods completely differently (core algorithm,
locking delegation, counting delegation). An interface correctly expresses
"these types share a contract" without implying shared internals, and it
lets a single class implement multiple unrelated contracts later if ever
needed (abstract classes forbid multiple inheritance of implementation in
Java). Interfaces are also the right default per the Java API design
guidelines unless shared implementation genuinely exists.

**2. Why are `Node` and `FrequencyList` private static nested classes instead of top-level classes in the package?**
They are implementation details of `LFUCache`'s O(1) algorithm with no
meaning or safe usage outside it — exposing them as top-level (even
package-private) classes would let other classes in the same package
manipulate raw nodes and corrupt the invariants `LFUCache` depends on.
Nesting them privately is the strongest encapsulation Java offers short of
a module boundary, and is the correct level of hiding for state whose
safety depends entirely on being mutated through exactly two methods
(`touch`, `evictLeastFrequentlyUsed`).

**3. What does `LFUCache` being `final` buy you, concretely?**
It closes off subclassing as an extension mechanism, which forces future
developers toward the two sanctioned extension points (the
`EvictionListener` hook and external composition via decorators) instead of
overriding `get`/`put` and risking the exact invariants discussed in
`STAFF_LEVEL_DEEP_DIVE.md` Q8. It's a design decision communicated through
the type system rather than a comment that can be ignored.

**4. Is `Node<K,V>` a value object? Does it need `equals`/`hashCode`?**
No to both. `Node` is a mutable, identity-based entity — the *same* `Node`
object migrates between `FrequencyList` buckets over its lifetime, and two
`Node`s are never meant to be compared for equality (there's exactly one
`Node` per key, tracked via `keyToNode`, and nothing in the codebase ever
puts `Node`s in a `Set`/uses them as `Map` keys or compares them). Adding
`equals`/`hashCode` here would be speculative and risks accidentally
breaking identity-based semantics (e.g. if someone later put `Node`s in a
`HashSet` expecting reference equality, a content-based `equals` override
would silently change behavior).

## SOLID Principles

**5. Where is Single Responsibility actually demonstrated, concretely, not just claimed?**
`LFUCache` owns exactly one responsibility: the LFU+LRU-tie-break eviction
algorithm and its O(1) guarantee. Thread-safety is `ConcurrentLFUCache`'s
sole responsibility; hit/miss/eviction counting is `StatsRecordingCache`'s
sole responsibility. Each class can be read, tested, and reasoned about
completely independently — `LFUCacheTest` never needs to think about
locking, and `ConcurrentLFUCacheTest` never needs to re-verify eviction
correctness (it only verifies the invariant holds under concurrency,
trusting `LFUCacheTest` already proved the single-threaded logic).

**6. Where is Open/Closed demonstrated?**
Adding `StatsRecordingCache` or `ConcurrentLFUCache` required zero
modifications to `LFUCache`'s source — both decorators are entirely new
files depending only on the public `Cache<K,V>` interface and (for stats)
the already-existing `EvictionListener` hook. Section 15 of
`STAFF_LEVEL_DEEP_DIVE.md` (Q3) demonstrates the same for a hypothetical
`remove(key)` addition.

**7. Where is Liskov Substitution relevant here, and is it ever at risk?**
Any `Cache<K,V>` must be substitutable wherever `Cache<K,V>` is expected —
all three implementations (`LFUCache`, `ConcurrentLFUCache`,
`StatsRecordingCache`) honor the exact same contract (same return types,
same meaning of "capacity," same "absent means `Optional.empty()`"
convention). The one place LSP could be *at risk*: if someone wrote a
`Cache<K,V>` implementation with different eviction semantics (say, pure
FIFO) but kept the same method signatures, code written against "LFU
behavior" assumptions (e.g. a test asserting a specific eviction order)
would silently break if that alternate implementation were substituted in
— a reminder that LSP is about *behavioral* substitutability, not just
matching method signatures.

**8. Interface Segregation — is a single four-method `Cache<K,V>` interface too coarse, or correctly scoped?**
Correctly scoped for this problem: every implementer needs all four
methods (`get`, `put`, `size`, `capacity`), and no client only wants a
subset. Splitting it further (e.g. a read-only `ReadableCache<K,V>` vs. a
`WritableCache<K,V>`) would be justified only if a real client existed that
should be prevented from writing — not built here because no such client
was identified, matching the "don't force abstractions" standard.

**9. Dependency Inversion — where does it show up?**
`ConcurrentLFUCache` and `StatsRecordingCache` both depend on the
`Cache<K,V>` abstraction, not on `LFUCache` concretely — their constructors
accept `Cache<K,V> delegate`. This is what allows
`StatsRecordingCache.forThreadSafeLFUCache` to compose a `ConcurrentLFUCache`
wrapping an `LFUCache` and hand the whole thing to `StatsRecordingCache`
without `StatsRecordingCache`'s code needing to know or care about that
inner structure.

## Design Patterns

**10. You used Decorator for `ConcurrentLFUCache`/`StatsRecordingCache` — why not the Proxy pattern instead, since they also "wrap" a delegate?**
Proxy and Decorator have near-identical structure (both wrap a same-typed
delegate), but differ in *intent*: Proxy controls or mediates access to the
delegate (e.g. lazy loading, remote access, access control) typically
without adding new behavior visible to the caller, while Decorator *adds*
behavior (locking, counting) while preserving the same interface. Both
`ConcurrentLFUCache` (adds mutual exclusion behavior) and
`StatsRecordingCache` (adds counting behavior) are additive, not access-
mediating, so Decorator is the structurally and semantically accurate name
for the relationship, even though the code shape is nearly identical to a
Proxy.

**11. Why not a Visitor pattern for the eviction-notification mechanism?**
Visitor solves "perform an operation that varies by concrete type, across
a closed set of types, without modifying those types." `EvictionListener`
has nothing to do with type-dispatch — there's exactly one kind of event
(an eviction) and one piece of data (key, value) to hand off. Visitor would
add a double-dispatch mechanism with no corresponding problem to solve
here; a plain functional callback interface is correctly minimal.

**12. If a second eviction policy (say, LRU) were added later, how would `CacheFactory` evolve, and would existing callers break?**
`CacheFactory` would gain parallel methods (`lruCache(capacity)`,
`threadSafeLruCache(capacity)`, etc.) without touching the existing
`lfuCache`/`threadSafeLfuCache`/etc. methods — purely additive, so no
existing caller's code changes or recompiles differently. This is the
concrete payoff of choosing "a handful of static factory methods" over a
single generic `createCache(Policy policy, int capacity)` method: each
policy's quirks (if any) can have their own factory signature without
forcing a lowest-common-denominator parameter list onto all policies.

## Data Structures

**13. Why doubly linked, not singly linked?**
`FrequencyList.remove(node)` needs O(1) removal given only a reference to
the node itself (not its position or a traversal from the head). With a
singly linked list, removing a node requires knowing its *predecessor*
(to repoint `prev.next`), which a node itself cannot provide without a
"prev" pointer — you'd need to track predecessors separately or traverse
from the head to find one, both defeating O(1). A doubly linked list makes
every node self-sufficient for its own removal.

**14. Why `HashMap` rather than `TreeMap` for `keyToNode`?**
No ordering requirement exists over `K` for the `keyToNode` map — its only
job is existence/lookup by key, so `HashMap`'s O(1) average lookup beats
`TreeMap`'s O(log n) for zero loss of needed capability. `TreeMap` would
only be justified if something needed keys in sorted order, which nothing
here does.

**15. Why do you need sentinel head/tail nodes in `FrequencyList` instead of nullable `head`/`tail` references?**
Without sentinels, every mutating operation needs explicit branches for
"the list is currently empty," "the node being removed is the only node,"
"the node being added becomes both head and tail," etc. — each an
opportunity for an off-by-one or null-pointer mistake, especially under the
added complexity of this being exactly the hot path the whole design's
correctness rests on. Sentinels make `head.next`/`tail.prev` always valid
references (possibly pointing at each other when the list is logically
empty), collapsing all those cases into the same uniform pointer-update
code.

**16. What's the space overhead per entry, concretely?**
Per entry: one `Node` object (key reference + value reference + int
frequency + two `Node` references — roughly 32–40 bytes of object overhead
plus the four reference/primitive fields, so on the order of 56–72 bytes
on a typical 64-bit JVM with compressed oops), one `HashMap.Node` entry
inside `keyToNode` (another ~32–48 bytes for the map's own internal entry
object), and a *shared* (not per-entry) `FrequencyList` object per distinct
active frequency value (bounded by `capacity`, not by entry count). This is
a real, non-trivial constant-factor overhead versus a theoretical "just
store the value" minimum — worth being able to estimate at staff level
when memory budgets come up, even approximately.

## Java Internals

**17. Why does `LFUCache.get` return `Optional<V>` and not just rely on `V` being nullable?**
Distinguishing "no such key" from "key present with value `null`" is
exactly the ambiguity `Optional` exists to resolve at the API level — if
`V` is allowed to be `null`, a bare nullable-`V` return makes "not found"
and "found, value is null" indistinguishable to the caller. `Optional<V>`
here would actually throw on `Optional.of(null)` if a null value were ever
stored, which is a known, accepted limitation (documented in README
section 3) rather than full null-value support (`Optional.ofNullable`
would be needed for that, deliberately not built to keep the contract
simple as scoped).

**18. Does `HashMap.computeIfAbsent` do anything special with respect to the Java Memory Model that matters here?**
No more than any other single-threaded `HashMap` operation — it's not a
concurrency primitive (unlike `ConcurrentHashMap.computeIfAbsent`, which
does have atomicity guarantees). In the single-threaded `LFUCache`, its
value here is purely ergonomic: atomically (within one thread's control
flow) "get-or-create" the `FrequencyList` for a frequency bucket in one
expression rather than a manual check-then-create, avoiding a bug class
(double-checking logic errors) that's irrelevant to correctness here but
good hygiene regardless.

**19. Why is `CacheStats` a `record` and not a plain class with a constructor and getters?**
A `record` gives correct `equals`/`hashCode`/`toString` based on its three
components for free, which matters because `CacheStats` instances are
compared/logged/asserted-on in tests (`StatsRecordingCacheTest`) — writing
those by hand risks the classic mistake of forgetting a field in `equals`
after adding a new counter later. The compact canonical constructor
(`public CacheStats { ... }`) also gives a clean place for the non-negative
validation without needing a separate static factory method.

**20. What does `volatile` or `synchronized` actually guarantee that a plain field write doesn't, and why didn't you reach for `volatile` on `minFrequency` instead of a full lock?**
`volatile` guarantees visibility (a write is immediately visible to other
threads) and prevents instruction reordering around that field, but it does
**not** provide atomicity for compound operations (read-modify-write, like
`minFrequency++`), nor does it do anything for the *other* shared
structures (`keyToNode`, `frequencyToList`) that also need protecting as
part of the same logical operation. Making `minFrequency` `volatile` alone
would be a half-measure solving a problem (single-field visibility) that
isn't the actual problem (multi-structure compound-operation atomicity) —
exactly why `ConcurrentLFUCache` uses a full lock around the whole
operation instead.

## Multithreading

**21. Could `synchronized` methods on `LFUCache` itself have worked instead of a separate `ConcurrentLFUCache` wrapper?**
Functionally, yes — `synchronized` on each public method would give the
same atomicity guarantee as the external `ReentrantLock`. The reason to
prefer the wrapper is architectural, not correctness-based: baking
`synchronized` into `LFUCache` forces every single-threaded caller to pay
monitor-acquisition overhead unconditionally, couples a cross-cutting
concern into the core algorithm class (harder to test the pure algorithm
in isolation without a lock in the picture), and makes it impossible to
later swap in a different concurrency strategy (e.g. `ReadWriteLock`,
sharding) without modifying `LFUCache` itself — violating Open/Closed for
no benefit `synchronized` methods provide over the external lock.

**22. What's a deadlock scenario you need to rule out, even though there's only one lock?**
With exactly one lock per `ConcurrentLFUCache` instance, self-deadlock is
impossible for `ReentrantLock` (it's reentrant) and cross-instance deadlock
is impossible unless code acquires two *different* `ConcurrentLFUCache`
instances' locks in inconsistent orders across threads (e.g. thread A locks
cache1 then tries to lock cache2, while thread B locks cache2 then tries to
lock cache1) — this would only arise from an operation spanning two
caches, which nothing in this design does, but it's worth naming as the
scenario to watch for if such a cross-cache operation were ever added
(e.g. "move an entry from one cache to another atomically").

**23. If `get` is called far more often than `put` (a read-heavy workload), does a `ReadWriteLock` help?**
No — and this is worth stating confidently rather than reflexively reaching
for `ReadWriteLock` because "read-heavy" usually triggers that reflex. A
`ReadWriteLock`'s benefit comes from letting multiple *readers* proceed
concurrently while writers are exclusive. But every `get` in this design
mutates state (frequency bump), so there is no "pure reader" category to
grant concurrent access to — every `get` needs the same exclusive access a
`put` does. This is the single most important "read-heavy doesn't imply
`ReadWriteLock` helps" lesson this problem teaches, and it's worth
contrasting explicitly with a cache where `get` truly is read-only (e.g. an
immutable config cache), where `ReadWriteLock` genuinely would help.

## Database and Persistence

**24. If this needed to survive a process restart, what's the minimal change, and what would you NOT do?**
Minimal change: on shutdown, serialize `keyToNode`'s key/value pairs (not
the frequency/recency bookkeeping, which isn't meaningful to persist
exactly — rebuilding it fresh at frequency 1 on reload is a reasonable,
documented approximation) to disk or an external store, and reload on
startup via a sequence of `put` calls. What you would **not** do: try to
serialize the internal `Node`/`FrequencyList` pointer structure directly —
it's an implementation detail with circular references (sentinel nodes,
prev/next cycles) that has no business being a durable format, and
serializing it would permanently couple the on-disk format to internal
implementation details that should be free to change.

**25. Why no transactions/isolation levels discussion for this problem?**
There's no multi-statement transactional boundary to speak of — every
public operation (`get`, `put`) is already a single atomic unit under
`ConcurrentLFUCache`'s lock, which is a strictly stronger guarantee than
most database isolation levels provide for a single statement (it's
effectively serializable, not just "read committed" or "repeatable read").
Transactions/isolation levels become relevant once an external datastore
is introduced (e.g. via an `EvictionListener` doing write-back), at which
point *that* interaction's consistency model is a question for the
specific datastore and listener design, not for `LFUCache` itself.

## Performance

**26. You claim O(1) amortized — what's a concrete workload that would make this look slow in a benchmark, despite the complexity proof being correct?**
A workload with pathological `hashCode()` collisions for the key type
(e.g. a buggy or adversarial `K.hashCode()` that always returns the same
value) degrades every `HashMap` operation toward O(n) (or O(log n) with
Java 8+'s treeification of long collision chains) regardless of the LFU
algorithm's own correctness — the complexity proof in README section 10 is
conditioned on `HashMap`'s *expected-case* behavior, and a workload that
violates that precondition will look slow in a benchmark without the LFU
design itself being at fault. This is worth naming explicitly as the gap
between "the algorithm is O(1)" and "this specific run was fast," which a
good benchmark methodology should control for (e.g. using a
well-distributed key type, not adversarial input).

**27. How would you benchmark this correctly, and what's a common mistake people make benchmarking caches?**
Use JMH with warm-up iterations (JIT compilation and inline caching take
time to stabilize — a cold-start measurement includes interpreter overhead
that isn't representative of steady-state performance), realistic key
distributions (a cache's hit rate, and therefore its *effective* per-op
cost mix of hit-path vs. miss-path vs. eviction-path work, depends heavily
on whether the benchmark's access pattern resembles real traffic, e.g. a
Zipfian distribution rather than uniform-random keys), and separate
single-threaded throughput numbers from concurrent-throughput numbers
(conflating the two hides exactly the lock-contention cost discussed in
`STAFF_LEVEL_DEEP_DIVE.md` Q10). A common mistake: benchmarking with a key
space small enough that the cache never actually evicts anything, silently
testing only the "always-hit" path and never exercising
`evictLeastFrequentlyUsed` at all.

## Failure Handling

**28. What's your story for a caller that passes a mutable object as `K` and later mutates it while it's a cache key?**
Covered in depth in `STAFF_LEVEL_DEEP_DIVE.md` Q16: this breaks `HashMap`'s
hash-bucket invariant, is a caller precondition violation (not a defect in
this cache), and is the same risk that exists for *any* `HashMap`-based
code, not specific to LFU — but a staff-level answer names it precisely
rather than hand-waving "don't do that."

**29. If `evictionListener.onEvict` throws, should the eviction be "rolled back" (the entry un-evicted)?**
Deliberately, no — section 13 of `README.md` documents this as accepted
partial-failure behavior: rolling back would mean catching the listener's
exception, re-inserting the just-removed node back into its old bucket
(itself needing to correctly restore `minFrequency` state, re-deriving
exactly the invariant-maintenance logic that was just unwound), and
*then* re-throwing or swallowing the listener's exception — all this
complexity in service of an "undo" semantic nobody asked for, and one that
papers over what is fundamentally the listener's bug (it threw), not the
cache's. The simpler, more honest contract: the eviction happened, the
insert that triggered it did not, and the exception propagates so the
caller knows something went wrong.

## Production Architecture

**30. In production, how would you detect that this cache's capacity is wrong (too small or too large) without manually reading logs?**
Export `CacheStats.hitRate()` (and raw counters) as a time-series metric
and alert on sustained low hit rate (signal: capacity too small, or access
pattern doesn't fit LFU's assumptions) — cross-referenced against the
cache's configured `capacity()` and typical working-set size if known.
Separately, track `evictionCount` rate relative to `put` rate: a
near-1:1 eviction-to-insert ratio under steady load is a strong, specific
signal of undersized capacity (nearly everything inserted gets evicted
almost immediately), distinct from and more actionable than hit rate alone
because it points directly at the capacity knob to turn.
