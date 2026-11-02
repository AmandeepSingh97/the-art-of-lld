# LFU Cache — Staff-Level Low-Level Design

## 1. Problem Understanding

Design an in-memory key-value cache with a fixed capacity that evicts the
**Least Frequently Used** entry when it is full. If multiple entries tie for
the lowest access frequency, evict the one that was used **least recently**
among them (LRU as a tie-breaker). Both `get` and `put` must run in O(1)
time.

**Real-world use case:** LFU is the right eviction policy whenever "how
often" matters more than "how recently" — e.g. caching hot product pages
where a handful of SKUs are viewed constantly and recency-based eviction
(LRU) would wrongly evict a perennially popular item just because nobody
happened to view it in the last five minutes, in favor of a one-time viral
spike item. Content-delivery edge caches, database buffer pools tuned for
skewed access distributions, and CDN object caches are classic examples.

**What the interviewer is evaluating:**
- Whether you independently arrive at the dual-HashMap-plus-doubly-linked-list
  structure, or need it hinted — and whether you can *justify* each piece
  rather than reciting it from memory.
- Whether you can state and prove the O(1) complexity claim precisely
  (not "it's basically O(1)").
- Whether you correctly handle the LRU tie-break, the update-vs-insert
  distinction, and capacity-0/1 boundary cases.
- At staff level: whether you can reason about making this safe for
  concurrent access, and whether you understand *why* this particular data
  structure resists the usual "just use `ConcurrentHashMap`" or
  "just shard it" answers.
- Code quality: encapsulation of the intrusive linked-list mechanics so
  callers (and even most of the surrounding code) never touch raw nodes.

**In-scope:** the core LFU algorithm and its O(1) proof; a clean `Cache<K,V>`
interface; a single-process thread-safe variant; basic instrumentation
(hit/miss/eviction counts) as a natural "what else would you add" extension;
eviction notification hook.

**Out-of-scope (explicitly, and why):** persistence/durability (this is a
cache, not a store — data loss on crash is acceptable by definition);
distributed/sharded caching across machines (a distinct system-design
problem layered on top of, not instead of, this LLD); TTL/expiry (a
genuinely separate orthogonal policy — mixing it in would muddy the LFU
algorithm the interview is about); generic pluggable eviction policies via
a strategy framework (see section 7 for why this is deliberately *not*
built, despite looking like an obvious "nice" abstraction).

## 2. Requirement Gathering

### Functional — essential
- `get(key)`: return the value if present; treat the call as an access that
  increases the key's frequency. Return "not found" (not an exception) for
  absent keys.
- `put(key, value)`: insert or update. A new key when the cache is full
  evicts exactly one entry per the LFU+LRU-tie-break policy. Updating an
  existing key's value also counts as an access (bumps frequency).
- Fixed capacity set at construction time.
- `size()`/`capacity()` introspection.

### Functional — optional (interview-stretch / "nice to have")
- Eviction notification (write-back, logging, metrics) — included here via
  `EvictionListener` because it's a one-interface addition with high payoff
  and is a common staff-level follow-up.
- Hit/miss/eviction counters — included via a decorator (`StatsRecordingCache`)
  because "how do you know if the cache is effective" is a near-universal
  follow-up question.
- Bulk operations (`getAll`, `putAll`), `remove(key)`, `clear()` — **not**
  implemented; explicitly flagged in section 17 as straightforward
  production extensions that don't change the core design, so building
  them in an interview just spends time without demonstrating new
  understanding.

### Non-functional
| Concern | Requirement | Interview-necessary? |
|---|---|---|
| Performance | O(1) amortized `get`/`put` | **Yes** — this is the headline constraint of the problem |
| Scalability | Single-process, bounded by `capacity` entries in memory | Yes, as a boundary — multi-node scaling is explicitly out of scope (section 1) |
| Availability | N/A (in-process library, not a service) | No |
| Consistency | Linearizable per-key under the thread-safe variant | Yes, once concurrency is raised |
| Extensibility | New cross-cutting concerns (stats, locking) addable without modifying the core policy class | Yes — demonstrates Open/Closed in a concrete way |
| Reliability | No partial/corrupted state after any single-threaded operation, even if the operation throws | Yes |
| Thread-safety | Must be explicitly discussed even if the "headline" design is single-threaded | Yes (see section 11) |

### Interview-necessary vs. production-extension split
Interview-necessary: the O(1) algorithm, correct tie-breaking, correct
update semantics, capacity boundary handling, and a reasoned concurrency
story. Production-extension (section 17): metrics export, distributed
coordination, persistence/write-through, dynamic resizing, warm-up/cold-start
handling, and operational monitoring.

## 3. Clarifying Questions

These are listed with *why they matter* and *how the answer would change
the design* — the assumption actually used in this repo is stated for each.

1. **"Is capacity fixed for the lifetime of the cache, or must it be
   resizable?"** Resizing changes eviction behavior: shrinking requires
   evicting multiple entries at once (still O(1) each, but now O(k) for a
   shrink by k) and the data structure design doesn't change, but the public
   API must gain a `resize(int)` method with its own invariants.
   **Assumption used: fixed at construction.** (Documented limitation,
   trivially extensible — see `STAFF_LEVEL_DEEP_DIVE.md`.)
2. **"What should `get` on a missing key return — null, a sentinel, throw,
   or `Optional`?"** This changes the public API contract and how null
   values (if allowed) are distinguished from "absent." **Assumption used:
   `Optional<V>`**, and values themselves may be `null`-free only by
   caller convention (not enforced) — `Optional.of(null)` throws NPE, so a
   cache of nullable values would need `Optional.ofNullable`, a change
   flagged but not built here to keep the API simple.
3. **"Does an update to an existing key's value count as an access for
   frequency purposes?"** This is the single most common source of
   incorrect LFU implementations. **Assumption used: yes**, matching the
   canonical LeetCode 460 specification and typical real-world cache
   semantics (an update is also a "use").
4. **"Is thread-safety required, and if so, what concurrency level —
   read-heavy, write-heavy, balanced?"** Determines whether a coarse lock
   suffices or finer-grained/read-write locking is worth the complexity.
   **Assumption used: both are deliverable** — single-threaded core plus an
   explicit thread-safe decorator, discussed in depth because "is it
   thread-safe" is near-guaranteed at staff level.
5. **"Should the cache evict proactively (background thread) or only
   lazily on the next `put` that needs room?"** Proactive eviction adds a
   scheduler, thread lifecycle management, and a new failure mode (evicting
   while a `get` is mid-flight) for no benefit in a capacity-bounded LFU
   cache (there's no TTL to expire "in the background" — eviction is only
   ever a response to a capacity constraint). **Assumption used: lazy,
   on-demand only.**
6. **"What happens with capacity 0? Capacity 1?"** Capacity 0 is a
   degenerate "cache that stores nothing" rather than an error — treating
   it as invalid would surprise callers who compute capacity dynamically
   and occasionally land on 0. **Assumption used: capacity 0 is legal and
   a no-op cache; negative capacity is a construction-time error.**
7. **"Must the eviction order be observable/testable, i.e., does the
   interviewer want to see the LRU-tie-break explicitly demonstrated?"**
   If yes, the design must expose enough determinism (not threading-caused
   nondeterminism) for a test to assert exact eviction order. **Assumption
   used: yes, and the single-threaded `LFUCache` is fully deterministic;
   determinism is intentionally not guaranteed once concurrency is
   introduced (ties under genuinely concurrent access are a race, by
   definition).**

## 4. Domain Modelling

| Element | Kind | Responsibility |
|---|---|---|
| `Cache<K,V>` | Interface | The only contract client code should depend on: `get`, `put`, `size`, `capacity`. Enables every decorator/alternate-implementation to be swapped in without touching call sites (Dependency Inversion). |
| `LFUCache<K,V>` | Core domain service (`final` class) | Owns the actual LFU+LRU-tie-break algorithm and all three internal structures. Single-threaded by design; see section 11 for why thread-safety is deliberately *not* mixed into this class. |
| `Node<K,V>` | Value-ish entity, private to `LFUCache` | Carries key/value/frequency/prev/next. Not a pure value object (it's mutable and identity-matters, since the same object migrates between buckets), but it is never exposed outside `LFUCache` — full encapsulation. |
| `FrequencyList<K,V>` | Domain service, private to `LFUCache` | An intrusive doubly linked list representing "all keys currently at frequency F, in recency order." One instance per distinct frequency present in the cache. |
| `EvictionListener<K,V>` | Strategy-shaped interface (functional) | Pluggable "what to do when something is evicted" — write-back, logging, metrics. Supplied at `LFUCache` construction time. |
| `ConcurrentLFUCache<K,V>` | Decorator | Adds thread-safety to any `Cache<K,V>` delegate via a single lock. |
| `StatsRecordingCache<K,V>` | Decorator | Adds hit/miss/eviction counters to any `Cache<K,V>` delegate. |
| `CacheStats` | Value object (record) | Immutable snapshot of counters; pure data, no behavior beyond derived accessors (`hitRate()`, `requestCount()`). |
| `CacheFactory` | Static factory | Assembles the correct decorator composition order so callers don't need to know it. |
| `InvalidCacheCapacityException` | Domain exception | Signals a precondition violation at construction time. |

**Relationships:**
- **Composition:** `LFUCache` *owns* its `Node`s and `FrequencyList`s —
  their lifecycle is entirely bound to the owning `LFUCache` instance; no
  other object ever holds a reference to them.
- **Aggregation:** a `Node` is linked into exactly one `FrequencyList` at a
  time, but the same `Node` *object* persists across many migrations
  between lists over its lifetime — the lists don't own the node's
  identity the way `LFUCache` does.
- **Association (uses-a):** `ConcurrentLFUCache`/`StatsRecordingCache`
  *use* a `Cache<K,V>` (composition by reference, not ownership of its
  internal state) via the interface, not a concrete type.
- **No inheritance** is used anywhere in this design beyond implementing
  an interface. Every "implementation shares behavior" temptation here
  (locking, stats) is better served by composition because the behaviors
  are genuinely orthogonal and need independent inclusion/exclusion/ordering
  — textbook case for favoring composition over inheritance.

## 5. Domain Invariants

| Invariant | Enforced where / how |
|---|---|
| `0 <= size() <= capacity()` at all times | `put` checks `size() >= capacity` and evicts *before* inserting a new key; never inserts past capacity. Verified by `LFUCacheTest.sizeNeverExceedsCapacityAcrossManyInsertions` and the concurrent analogue. |
| Every key in `keyToNode` has its `Node` linked into exactly one `FrequencyList`, at the bucket matching `node.frequency` | Maintained by `touch()`, the single method that ever changes a node's frequency or bucket membership; it always removes from the old bucket before adding to the new one. |
| `minFrequency` always names a bucket that is either absent (cache empty) or non-empty and truly minimal | `touch()` only increments `minFrequency` when the bucket it just vacated *was* the minimum and is now empty — this is the one invariant requiring the most careful proof (see section 10). `put` unconditionally resets it to 1 for a new key, which is always safe since 1 is a lower bound on any possible frequency. |
| A node's frequency only ever increases, by exactly 1, per access | `touch()` does `node.frequency = oldFrequency + 1` unconditionally — there is no code path that decreases or skips frequency. |
| `FrequencyList` order is recency order (head = most recent) | `addToHead` always inserts at the head; `removeTail` always removes the least-recently-touched node, which is exactly the LRU tie-break rule. |
| No empty `FrequencyList` is left reachable from `frequencyToList` | Both `touch()` and `evictLeastFrequentlyUsed()` call `frequencyToList.remove(freq)` whenever a bucket becomes empty, preventing unbounded growth of empty buckets and preventing `evictLeastFrequentlyUsed` from ever looking at a stale empty bucket. |
| Capacity is non-negative and immutable after construction | Checked once in the constructor (`InvalidCacheCapacityException` for negative values); the field is `final`. |

## 6. Architecture and Class Diagram

Full diagrams are in `diagrams/`:
- `diagrams/component-diagram.md` — how the public interface, core policy,
  and decorators compose.
- `diagrams/class-diagram.md` — full class diagram including the private
  `Node`/`FrequencyList` nested classes, matching `src/` exactly.
- `diagrams/sequence-get.md` — `get(key)` on hit and miss.
- `diagrams/sequence-put-eviction.md` — `put(key, value)` when it must
  evict first.

These diagrams are generated to match the actual code in
`src/main/java/lld/lfucache/`; if you change a method signature there,
update the corresponding diagram.

## 7. Design Patterns

| Pattern | Why needed | Where applied | Alternative considered | Why rejected |
|---|---|---|---|---|
| **Decorator** | Thread-safety and instrumentation are cross-cutting concerns independent of the eviction policy and independent of each other; callers need to opt in/out and choose composition order. | `ConcurrentLFUCache`, `StatsRecordingCache` both wrap `Cache<K,V>`. | Subclassing `LFUCache` and overriding `get`/`put` to add locking/counters. | Would force `LFUCache` to be non-final and expose its mutable internals (`Node`, `FrequencyList`, `minFrequency`) to subclasses via `protected`, breaking encapsulation of the exact invariants that make the O(1) proof hold. It also doesn't compose — you'd need `LockingStatsLFUCache` *and* `StatsLockingLFUCache` subclasses for both orderings. |
| **Sentinel node idiom** (not GoF, but a load-bearing design idiom) | Removes every null-check branch from `FrequencyList.addToHead`/`remove`/`removeTail`, which matters because this code is the hot path whose complexity claims this whole design rests on. | `FrequencyList`'s permanent `head`/`tail` dummy nodes. | Null-checked `if (list.isEmpty()) ... else ...` branches. | Functionally equivalent but adds branches and edge cases (is `head == null`? is `head == tail`?) that are easy to get subtly wrong under tie-break logic; the sentinel version is uniform and has been the standard idiom for exactly this reason since the classic LRU-cache literature. |
| **Observer** (via `EvictionListener`, a single-method functional interface) | A narrowly-scoped hook so callers can react to evictions (e.g. write-back, logging) without the core class needing to know what reaction is needed. | `LFUCache` constructor takes an `EvictionListener<K,V>`; `evictLeastFrequentlyUsed()` invokes it exactly once per eviction. | A richer `CacheEventListener` with `onPut`/`onGet`/`onEvict`/`onExpire`. | Rejected as speculative generality: nothing in this problem's requirements calls for hit/put event hooks, and adding them "in case someone wants them" is exactly the overengineering this spec warns against. The chosen interface can grow additively (default methods) if that need materializes. |
| **Static Factory Method** (not Abstract Factory/Builder) | `CacheFactory` hides the correct decorator composition order (`StatsRecordingCache` around `ConcurrentLFUCache` around `LFUCache`, with the eviction listener wired through) so callers don't need to know or get it wrong. | `CacheFactory.lfuCache/threadSafeLfuCache/instrumentedLfuCache/instrumentedThreadSafeLfuCache`. | A fluent `CacheBuilder` (`.withCapacity(n).threadSafe().instrumented().build()`). | For four concrete, enumerable combinations, a builder is more machinery (a mutable builder object, validation-at-build-time, a fluent API surface to document and test) for no expressiveness gain over four static methods. If the combination space grows (e.g. a second eviction policy, pluggable lock strategies), a builder becomes justified — noted as a production-evolution trigger in section 17, not built preemptively. |
| **Strategy — deliberately NOT used for eviction policy** | A `EvictionPolicy` interface with `LFUPolicy`/`LRUPolicy` implementations is the "obvious" pattern-matching move once you see "eviction policy" in a problem statement. | — (not present in `src/`) | A generic `Cache<K,V,P extends EvictionPolicy>` where the policy decides what to evict. | Rejected because LFU's O(1) guarantee is *inseparable* from its specific data structure (the frequency-bucketed DLLs): an `EvictionPolicy` abstraction generic enough to also describe LRU (single DLL) or FIFO (queue) would either (a) leak the concrete structure through the abstraction anyway, defeating the point, or (b) force LFU's bucket management down to the level of "decide which key to evict," which is not where LFU's actual complexity lives — the complexity is in *maintaining the buckets incrementally*, not in a final O(1) decision step. This is a case where forcing a pattern would reduce clarity, which the engineering standards explicitly warn against. |

## 8. API and Method Design

```java
public interface Cache<K, V> {
    Optional<V> get(K key);
    void put(K key, V value);
    int size();
    int capacity();
}
```

- `get`/`put` take raw `K`/`V` — no request/response wrapper objects. A
  cache's API surface is intentionally minimal; introducing
  `GetRequest`/`GetResponse` DTOs here would be needless ceremony for a
  two-argument, two-return-value contract with no versioning or
  cross-process-boundary concerns (contrast with, say, a REST API, where
  request/response objects earn their keep).
- **Validation responsibilities:** `LFUCache`'s constructor validates
  `capacity >= 0` and throws `InvalidCacheCapacityException` (unchecked —
  a precondition violation, not a recoverable condition). `get`/`put` do
  **not** null-check `key` beyond what `HashMap` itself does (a `null` key
  is technically legal in a `HashMap` and therefore in this cache too,
  matching `Map` conventions rather than inventing a stricter contract the
  interviewer didn't ask for).
- `EvictionListener<K,V>.onEvict(K, V)` — supplied at construction, not a
  method on `Cache<K,V>` itself, because it is an `LFUCache`-specific
  construction-time collaborator, not a general cache capability (an LRU
  cache would want the identical hook, so it conceptually belongs at the
  implementation-constructor level, not the shared interface).
- Exceptions: `InvalidCacheCapacityException extends IllegalArgumentException`
  (unchecked, construction-time only). No checked exceptions anywhere —
  there is no recoverable failure mode in an in-memory cache's `get`/`put`
  that a caller could meaningfully catch and retry differently.

## 9. Core Implementation

The full, compiling implementation lives in `src/main/java/lld/lfucache/`:

- [`Cache.java`](src/main/java/lld/lfucache/Cache.java) — the public contract.
- [`LFUCache.java`](src/main/java/lld/lfucache/LFUCache.java) — the core
  O(1) algorithm: `Node`, `FrequencyList`, `touch()`, `evictLeastFrequentlyUsed()`.
- [`ConcurrentLFUCache.java`](src/main/java/lld/lfucache/ConcurrentLFUCache.java) —
  single-lock thread-safety decorator.
- [`StatsRecordingCache.java`](src/main/java/lld/lfucache/StatsRecordingCache.java) —
  hit/miss/eviction counting decorator.
- [`CacheStats.java`](src/main/java/lld/lfucache/CacheStats.java) —
  immutable metrics snapshot.
- [`EvictionListener.java`](src/main/java/lld/lfucache/EvictionListener.java) —
  eviction notification hook.
- [`CacheFactory.java`](src/main/java/lld/lfucache/CacheFactory.java) —
  assembly helpers.
- [`InvalidCacheCapacityException.java`](src/main/java/lld/lfucache/InvalidCacheCapacityException.java)
- [`LFUCacheDemo.java`](src/main/java/lld/lfucache/LFUCacheDemo.java) —
  runnable `main` demonstrating basic eviction, the LRU tie-break,
  instrumentation, and concurrent access.

The single most important method — because it is where the O(1) invariant
is either maintained or broken — is `LFUCache.touch()`:

```java
private void touch(Node<K, V> node) {
    int oldFrequency = node.frequency;
    FrequencyList<K, V> oldList = frequencyToList.get(oldFrequency);
    oldList.remove(node);
    if (oldList.isEmpty()) {
        frequencyToList.remove(oldFrequency);
        if (oldFrequency == minFrequency) {
            minFrequency++;
        }
    }
    node.frequency = oldFrequency + 1;
    frequencyToList
            .computeIfAbsent(node.frequency, freq -> new FrequencyList<>())
            .addToHead(node);
}
```

Run it yourself:
```bash
javac -d out $(find src -name '*.java')
java -cp out lld.lfucache.LFUCacheDemo
```

## 10. Data Structures and Algorithms

### What's used and why

| Structure | Purpose | Why this one |
|---|---|---|
| `HashMap<K, Node<K,V>>` | O(1) key existence check and direct node access | The only structure offering O(1) average-case lookup by arbitrary key; no ordering requirement on this map, so no need for `LinkedHashMap`/`TreeMap` here. |
| `HashMap<Integer, FrequencyList<K,V>>` | O(1) access to "the bucket of all keys at frequency F" | Frequencies are a bounded, sparse integer domain (at most `capacity` distinct values can be "active" at once, though the frequency *values themselves* are unbounded in principle) — a hash map handles this sparsity better than, say, an array indexed by frequency, which would need unbounded resizing as frequencies grow (a key accessed a million times has frequency 1,000,000). |
| Intrusive doubly linked list (`Node.prev/next`, `FrequencyList`) | O(1) removal from an arbitrary interior position, O(1) insertion at head, O(1) removal from tail | This is the crux: a node needs to move between buckets in O(1) *without searching* for it in the destination or source list. A `java.util.LinkedList` hides its internal nodes behind an index-based/iterator-based API — removing a known element from a `LinkedList` is O(n) because you must first find it (`LinkedList.remove(Object)` scans). An intrusive list sidesteps the search entirely because the `Node` already knows its own neighbors. |
| `int minFrequency` | O(1) identification of the next eviction candidate's bucket | The alternative — finding the minimum key across `frequencyToList` — would be O(number of distinct active frequencies) with a plain `HashMap`, or O(log n) with a `TreeMap`/heap keyed by frequency. Incrementally maintaining the minimum (it can only ever increase on `touch`, and resets to 1 on any new insert) turns this into an O(1) field read. |

### Rigorous O(1) complexity proof

Claim: both `get(key)` and `put(key, value)` perform O(1) work, where "work"
means a constant number of hash-map operations (each O(1) expected time)
plus a constant number of pointer read/writes — independent of `n`
(current size) and independent of `capacity`.

**`get(key)`:**
1. `keyToNode.get(key)` — one `HashMap` lookup: O(1) expected.
2. Miss path: return immediately. O(1) total.
3. Hit path: calls `touch(node)`, analyzed below as O(1). Total: O(1).

**`touch(node)` — the shared core of `get`(hit) and `put`(update):**
1. `frequencyToList.get(oldFrequency)` — one `HashMap` lookup: O(1).
2. `oldList.remove(node)` — exactly four pointer writes
   (`node.prev.next`, `node.next.prev`, and clearing `node.prev`/`node.next`)
   and one decrement of `size`. No traversal: the node already holds direct
   references to its neighbors. O(1).
3. `oldList.isEmpty()` — O(1) field read.
4. *(Conditionally)* `frequencyToList.remove(oldFrequency)` — one `HashMap`
   removal: O(1) expected. This branch executes **at most once** per call.
5. *(Conditionally)* `minFrequency++` — O(1). This is the step requiring a
   correctness argument, not just a complexity one (see below).
6. `node.frequency = oldFrequency + 1` — O(1).
7. `frequencyToList.computeIfAbsent(...)` — one `HashMap` lookup, and at
   most one `HashMap` insertion (`new FrequencyList<>()`) when the target
   bucket doesn't yet exist: O(1) expected.
8. `addToHead(node)` — exactly four pointer writes and one increment: O(1).

Every step is O(1); the total is a fixed constant number of O(1) steps,
hence `touch` is O(1) (amortized, inheriting `HashMap`'s amortized O(1) for
resizing on rare occasions — see note below).

**`put(key, value)`:**
1. `keyToNode.get(key)` — O(1).
2. Update path (`existing != null`): assign `existing.value`, call
   `touch(existing)` (O(1) per above). Total O(1).
3. Insert path: `keyToNode.size()` — O(1) (`HashMap.size()` is a maintained
   counter, not a traversal). Conditionally calls
   `evictLeastFrequentlyUsed()` (analyzed below, O(1)), then constructs a
   `Node`, inserts into `keyToNode` (O(1)), inserts into the frequency-1
   bucket via `computeIfAbsent`/`addToHead` (O(1) each, as in `touch` steps
   7–8), and sets `minFrequency = 1` (O(1)). Total O(1).

**`evictLeastFrequentlyUsed()`:**
1. `frequencyToList.get(minFrequency)` — O(1).
2. `minList.removeTail()` — internally one more `remove(node)` call on the
   tail's predecessor link: O(1), by the same pointer-only argument as step
   2 of `touch`.
3. `keyToNode.remove(evicted.key)` — O(1).
4. Conditional `frequencyToList.remove(minFrequency)` — O(1), executes at
   most once.
5. `evictionListener.onEvict(...)` — O(1) **by contract** (documented on
   `EvictionListener`: listeners must be fast); this is a caller-supplied
   function, so the overall O(1) claim is conditioned on listeners
   upholding that contract, exactly as a `Comparator` must uphold its own
   contract for a sort algorithm's complexity claims to hold.

**Why the `minFrequency++` step is *correct*, not just O(1):** when
`oldList` (the bucket at `oldFrequency`) becomes empty inside `touch`, and
`oldFrequency == minFrequency`, the new minimum cannot be anything other
than `minFrequency + 1`. Proof: frequencies only ever increase by exactly 1
per `touch` call (never skip a value), so no node can have a frequency
strictly between `oldFrequency` and `oldFrequency + 1`. Since the bucket at
`oldFrequency` is now empty and (by the invariant from section 5) every
active bucket is non-empty, the smallest frequency with a non-empty bucket
must be `>= oldFrequency + 1`. It cannot be larger than `oldFrequency + 1`
either in the one case that matters for this call, because the node being
moved is *about to be* inserted at exactly `oldFrequency + 1`, which will
therefore be non-empty immediately after — so `minFrequency + 1` is both a
valid lower bound and immediately achieved. Hence `minFrequency++` is exact,
not an approximation needing a fallback scan.

**Amortized vs. worst-case caveat (full honesty, not hand-waving):** `HashMap`
operations are O(1) in the *expected/amortized* sense — a worst-case single
call can be O(n) during a resize, and pathological hash collisions can
degrade to O(log n) per bucket (Java 8+ treeifies long collision chains).
This is the same caveat that applies to literally every "O(1) HashMap"
claim in any Java interview answer and is not specific to this design;
stating it explicitly here is the staff-level move — as opposed to leaving
it implicit, which is also fine for a 45-minute interview but not for the
rigorous proof this document commits to.

### Alternatives considered and rejected

| Alternative | Complexity | Why rejected |
|---|---|---|
| `PriorityQueue<Node>` keyed by `(frequency, lastUsedTimestamp)` | O(log n) per `get`/`put` (heap sift) | Violates the O(1) requirement directly. Also: a binary heap does not support O(1) arbitrary-element removal/update-key, which `get` on an *existing* non-root entry requires — you'd need an auxiliary index (key → heap position) just to find the entry, and *still* pay O(log n) to re-heapify after the frequency bump. |
| `TreeMap<Integer, Set<K>>` ordered by frequency | O(log n) per `get`/`put` (red-black tree operations) | Same complexity problem as the heap, for the same underlying reason: anything that keeps frequencies in a *globally sorted* structure pays a logarithmic price to maintain that global order, when the problem only ever needs the *minimum*, not a full ordering. |
| Single `LinkedHashMap` with a custom comparator-like reordering on access | Not actually implementable for LFU in O(1) | `LinkedHashMap`'s access-order mode gives LRU "for free" (it's built for exactly that), but it has no notion of *frequency* at all — bolting frequency tracking onto it would require a parallel frequency map anyway, at which point you've reinvented (a worse version of) this design. |
| Doubly linked list ordered by frequency only (no per-frequency buckets) | O(n) worst case to find where a bumped node should move to | Without bucketing by frequency, finding "where in the single list does this node belong now" requires walking past every node of the same or lower frequency — exactly the search this design's bucketing is designed to avoid. |

## 11. Concurrency and Thread Safety

### Shared mutable state
`LFUCache` has three pieces of mutable state shared across any threads that
call into the same instance: `keyToNode`, `frequencyToList`, and
`minFrequency`, plus the mutable fields inside every `Node`
(`value`, `frequency`, `prev`, `next`).

### Race conditions if unsynchronized
Two threads calling `get`/`put` concurrently on a bare `LFUCache` can:
- Both read the same `oldList` for the same node, both call `remove(node)`,
  and corrupt the list (e.g. `node.prev.next` gets written twice with
  different intents, orphaning or duplicating list entries).
- Observe `minFrequency` mid-update, e.g. thread A reads
  `minFrequency == 3` to decide an eviction target while thread B is
  concurrently incrementing it to 4 because thread A's bucket just became
  empty from B's perspective — leading `evictLeastFrequentlyUsed` to look
  up a bucket (`frequencyToList.get(3)`) that no longer exists, returning
  `null`, then NPEing on `minList.removeTail()`.
- Violate the `size() <= capacity()` invariant: two threads can both
  observe `size() < capacity` (stale read) and both proceed to insert
  without evicting, overshooting capacity.
- Even `HashMap` itself is not thread-safe for concurrent structural
  modification (put while another thread iterates/resizes) — this can
  corrupt the map's internal bucket array or (notoriously, pre-Java-8) spin
  into an infinite loop during a resize race.

None of this is hypothetical pessimism — it's a direct consequence of
`HashMap` and raw object field writes having **no happens-before
relationship** across threads without external synchronization, per the
Java Memory Model. A thread can legally never observe another thread's
write to `minFrequency` at all without a lock, volatile, or other
synchronization establishing that relationship.

### Chosen design: `ConcurrentLFUCache`, a single coarse-grained lock

```java
public final class ConcurrentLFUCache<K, V> implements Cache<K, V> {
    private final Cache<K, V> delegate;
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public Optional<V> get(K key) {
        lock.lock();
        try {
            return delegate.get(key);
        } finally {
            lock.unlock();
        }
    }
    // put/size follow the same lock/try/finally shape
}
```

**Why a single lock, justified (not asserted):**
- **Lock granularity:** the entire operation (`get` or `put`) must be
  atomic as a unit, because a single logical operation touches multiple
  shared structures (`keyToNode`, two `FrequencyList` buckets, and possibly
  `minFrequency`) that must move together consistently. A lock held for
  anything less than the full method body reintroduces the exact races
  described above.
- **Deadlock freedom:** a single lock per cache instance cannot deadlock
  against itself. `ReentrantLock` additionally tolerates reentrant calls
  from the same thread (e.g. if an `EvictionListener` ever called back into
  the same cache instance — documented as forbidden regardless, since that
  would be a logical, not just locking, hazard).
- **Visibility:** acquiring/releasing a `ReentrantLock` establishes a
  happens-before edge (per `java.util.concurrent` memory-model guarantees),
  so every thread's view of `keyToNode`/`frequencyToList`/`minFrequency`
  after acquiring the lock reflects every prior lock-protected mutation by
  any other thread. This is what makes the single lock *sufficient*, not
  just convenient.
- **Why not striped locking:** see `ConcurrentLFUCache`'s class Javadoc and
  `STAFF_LEVEL_DEEP_DIVE.md` for the full argument — summarized, striping by
  key doesn't help because frequency buckets are shared across keys that
  would hash to different stripes, and striping by frequency doesn't help
  either because `minFrequency` is still global state every operation can
  touch.
- **Why not lock-free:** a single logical "bump frequency" operation is a
  multi-location update (unlink, relink, possibly delete a bucket, possibly
  bump `minFrequency`) with no single CAS-able word capturing all of it
  atomically. A correct lock-free version needs something like a
  multi-word compare-and-swap protocol (effectively a mini software
  transactional memory) — real research exists here, but it is
  substantially more complex than this problem's scope justifies, and the
  critical section this lock protects is already O(1) work, so the
  contention cost of a single lock is bounded and predictable rather than
  a genuine bottleneck.
- **Optimistic vs. pessimistic:** this design is pessimistic (a lock is
  always acquired, win or lose). An optimistic alternative (e.g.
  read-then-validate-then-retry via a version counter) would help if reads
  vastly outnumbered writes *and* could be done lock-free — but every `get`
  in LFU is also a write (frequency bump), so there is no true "read path"
  to optimize this way, unlike, say, a read-mostly configuration cache.
  This is a problem-specific reason optimistic locking buys nothing here,
  not a generic dismissal of the technique.

### Concurrent collections considered and rejected
`ConcurrentHashMap` for `keyToNode` alone does **not** make the cache
thread-safe: it would make individual map operations atomic, but the
*composite* operation ("check existence, then conditionally evict, then
insert, keeping three structures consistent") still needs an external lock
around the whole sequence — using a concurrent map for one of three
interdependent structures without protecting their *joint* consistency is a
common, dangerous half-measure.

### Idempotency and failure recovery
`put` is naturally idempotent for identical repeated calls with the same
key/value (each call still correctly bumps frequency, which is an
intentional, documented side effect, not a correctness bug). If `put`
throws mid-operation (e.g. an `EvictionListener` throws), the lock is still
released via `finally`, but the cache can be left having evicted an entry
without having inserted the new one — a genuine partial-failure mode,
documented explicitly in section 13 rather than silently accepted.

## 12. Persistence and Database Design

This is intentionally a **pure in-memory** design with no database layer.
**Why a database is unnecessary here:** a cache's entire reason for existing
is to be faster than durable storage; adding a database underneath would
either (a) make every operation synchronously I/O-bound, defeating the
purpose, or (b) require an asynchronous write-back path, which is a real
and common production pattern but is an *additional* system on top of this
LLD, not part of it — exactly the kind of "don't over-distribute/over-build
an LLD problem" the spec warns against (see section 14 for where this is
revisited as a production extension).

If write-back-on-eviction were required (e.g. "evicted entries must be
persisted to a database"), the natural extension point is already present
and requires **no change to `LFUCache`'s internals**: implement an
`EvictionListener<K,V>` that performs the write (synchronously, or by
enqueueing to an async writer) and pass it to the `LFUCache` constructor.
This is exactly why the Observer hook was included despite the "no
speculative generality" principle in section 7 — it is a one-interface,
zero-internal-change extension point for a request pattern ("what happens
to evicted data") that is realistically asked in almost every cache
interview, distinguishing it from genuinely speculative additions like a
full event bus.

## 13. Failure Scenarios

| Scenario | Behavior / handling |
|---|---|
| `get`/`put` called with a `null` key | Delegates to `HashMap`'s own null-key handling (legal, hashes to bucket 0). Not a failure; documented as matching `Map` semantics (section 8). |
| Capacity constructed as negative | `InvalidCacheCapacityException` thrown immediately at construction — fails fast rather than allowing an unusable cache to exist. |
| `EvictionListener.onEvict` throws a `RuntimeException` | Propagates out of `put` *after* the node has already been removed from `keyToNode` and its `FrequencyList` — i.e. the eviction itself is **not** rolled back, but the new entry is **not** inserted either. This is a genuine partial-failure state (cache lost one entry, gained none) and is a direct, documented consequence of choosing a synchronous listener over, say, a try/collect-errors/insert-anyway approach. The trade-off: a synchronous listener that can veto/observe before commit is simpler to reason about than a design that must decide whether to insert despite a failed write-back; callers needing transactional write-back should catch and handle inside their own listener rather than relying on `LFUCache` to paper over it. |
| Duplicate `put` of the same key/value | Not a failure — treated as a legitimate access, per section 3 clarifying question 3. |
| Resource exhaustion (OOM from very large values at high capacity) | Out of this design's control — the JVM heap is the limiting resource, and no in-memory cache design can protect against values large enough to exhaust it. A production extension (section 17) would track approximate memory usage per entry and evict on a byte-size budget rather than (or in addition to) an entry-count budget. |
| Concurrent modification corrupting the unsynchronized `LFUCache` | By design, never happens to `ConcurrentLFUCache`, whose single lock serializes every operation (section 11). Using bare `LFUCache` concurrently is a misuse of the API, documented prominently in its class Javadoc precisely so this failure mode is a documentation/usage error, not a silent landmine. |
| Timeouts / retries | Not applicable: every operation here is a pure in-memory, non-blocking (aside from lock acquisition) computation with no I/O, so there is nothing to time out on in the core design. Once an `EvictionListener` performs I/O (section 12), timeout/retry policy becomes that listener's responsibility, not the cache's. |
| Cleanup / resource leaks | No external resources (file handles, sockets, threads) are held by `LFUCache`/`ConcurrentLFUCache`/`StatsRecordingCache` — there is nothing to close. If a future `EvictionListener` holds a resource (e.g. a database connection), that listener's lifecycle management is its own responsibility. |

## 14. Performance and Scalability

**Bottlenecks and fixes, single-process:**
- The single lock in `ConcurrentLFUCache` is the only bottleneck intrinsic
  to this design under high write concurrency — every `put`/`get` is fully
  serialized. Fix considered: since the critical section is O(1), the fix
  that actually matters at scale is *reducing contention by sharding whole
  cache instances* (see below), not finer-grained locking within one
  instance (ruled out in section 11 for correctness reasons, not just
  performance ones).
- `HashMap` resize pauses (amortized O(1), but a real O(n) event when they
  happen) can be avoided by sizing the initial `HashMap` capacity to the
  cache's fixed `capacity` up front (a cheap, safe micro-optimization,
  intentionally not done in `LFUCache` itself to keep the reference
  implementation's intent maximally readable — flagged here as the kind of
  tuning that belongs in a profiling-driven pass, not a first cut).

**Multi-threaded extension (still single-process):** shard the key space
across N independent `LFUCache` instances (e.g. `hash(key) % N` picks the
shard), each with its own lock. This trades a single global LFU ordering
for N independent, smaller LFU orderings — a deliberate, documented
approximation (section 16 covers this trade-off), reducing lock contention
roughly N-fold for a cache whose access pattern is well-distributed across
shards.

**Distributed extension (explicitly a *separate* system, not scope creep
here):** a single in-process `LFUCache` obviously cannot serve multiple
machines. Distributed LFU is usually approximated rather than implemented
exactly, because exact global LFU ordering across nodes would require
coordination on every access (defeating the purpose of caching for
latency). The standard real-world approach layers *approximate* frequency
counting (e.g. Count-Min Sketch, as used in Caffeine's W-TinyLFU) per node,
with independent per-node eviction — explicitly **not** built here, because
building it would be solving a different, much larger distributed-systems
problem under the banner of an LLD interview, which the master spec
explicitly discourages ("do not over-distribute an LLD problem").

## 15. Testing Strategy

Representative tests live in `tests/lld/lfucache/`:

- **Unit / correctness** — [`LFUCacheTest.java`](tests/lld/lfucache/LFUCacheTest.java):
  basic get/put, update-counts-as-access, LFU eviction, the LRU tie-break
  (`tiesAtSameFrequencyAreBrokenByLeastRecentlyUsed`), repeated-access
  protection from eviction, eviction listener correctness (including that
  it's *not* invoked for plain updates).
- **Boundary** — capacity 0 (`zeroCapacityCacheNeverStoresAnything`) and
  capacity 1 (`capacityOneAlwaysEvictsThePreviousEntryOnAnyNewKey`) in the
  same file.
- **Negative** — `negativeCapacityThrowsInvalidCacheCapacityException`.
- **Invariant-style property test** —
  `sizeNeverExceedsCapacityAcrossManyInsertions` asserts `size() <=
  capacity()` after every one of 500 insertions, not just at the end.
- **Concurrency** — [`ConcurrentLFUCacheTest.java`](tests/lld/lfucache/ConcurrentLFUCacheTest.java):
  many threads hammering an overlapping key space, asserting the size
  invariant holds and no exceptions escape under contention.
- **Decorator-specific** — [`StatsRecordingCacheTest.java`](tests/lld/lfucache/StatsRecordingCacheTest.java)
  verifies hit/miss counting, hit-rate math (including the "no requests
  yet" edge case being `0.0` not `NaN`), and — importantly — that
  evictions triggered *inside* the wrapped `LFUCache` are actually observed
  by the decorator, plus the honest-zero behavior when a delegate isn't
  wired with the shared listener.
- **Composition / integration** — [`CacheFactoryTest.java`](tests/lld/lfucache/CacheFactoryTest.java)
  exercises every factory method, with dedicated assertions for the
  thread-safe-*and*-instrumented combination (the composition most likely
  to be wired in the wrong order).

**Deliberately not included:** a test that asserts an unsynchronized
`LFUCache` *will* corrupt its invariants under concurrent access. Such a
test would be a true "negative control" proving `ConcurrentLFUCache`'s
necessity, but whether a data race manifests within a bounded number of
iterations is inherently timing/scheduler/JIT-dependent — asserting "the
race must trigger" would make the suite flaky by construction. This
experiment is documented as a manual, non-CI exercise in
`STAFF_LEVEL_DEEP_DIVE.md` instead.

Run all tests:
```bash
javac -d out -cp junit-platform-console-standalone.jar $(find src tests -name '*.java')
java -jar junit-platform-console-standalone.jar --class-path out --scan-class-path
```
(25 tests, all passing, as verified for this repository — see the final
verification note at the end of this document's section 17 area / the
accompanying task summary.)

## 16. Trade-offs and Alternatives

| Decision | Alternative | Pros of alternative | Cons of alternative | Why this design wins | When the alternative wins |
|---|---|---|---|---|---|
| Intrusive doubly linked list per frequency bucket | `LinkedHashSet<Node>` per bucket (insertion order ~ recency if always re-inserted) | Less custom code; reuses JDK collection | `remove(Object)` + re-`add` is still effectively O(1) amortized here, but loses the explicit head/tail semantics and sentinel-node branch-elimination benefit; also re-inserting to simulate "move to head" requires `remove` then `add`, which is two hash operations instead of pure pointer surgery | Explicit pointer control makes the O(1) proof tight and auditable, and avoids a second layer of hashing inside each bucket for something that's already uniquely identified by object reference | A quick prototype where code size matters more than a rigorous complexity proof |
| Single coarse-grained lock for concurrency | Sharded cache (N independent `LFUCache`s) | Better horizontal scalability under contention | Approximates (not exact) global LFU ordering; adds a routing layer | Simpler, exactly correct, and the O(1) critical section makes raw lock contention a secondary concern versus correctness risk | High-throughput production systems where approximate-but-fast beats exact-but-serialized |
| `Optional<V>` return from `get` | Return `null` for absent, or throw `NoSuchElementException` | `null` avoids `Optional` allocation overhead; exceptions make "absent" impossible to ignore | `null` return requires callers to remember to null-check (easy to forget, classic NPE source); exceptions for a *common*, expected case (cache miss) is an anti-pattern — exceptions should signal exceptional conditions | `Optional` makes "might be absent" explicit in the type signature, and a cache miss is a completely routine, expected outcome, not exceptional | A hot path where `Optional` allocation is empirically measured to matter (rare in practice on modern JVMs with escape analysis, but a legitimate reason in an ultra-low-latency system) |
| Decorator composition for stats/locking | Build stats and locking directly into `LFUCache` | One class, no composition ceremony, possibly marginally faster (no extra indirection) | Couples unrelated concerns; forces every user to pay for locking/stats overhead even when not needed; harder to test the pure algorithm in isolation | Matches Single Responsibility; zero-cost for callers who don't need the extra behavior; each concern independently testable | A performance-critical inner loop where even one extra virtual dispatch per call is unacceptable and composition's indirection is empirically proven to matter |
| Eviction listener as a constructor-supplied callback | A `Cache<K,V>.subscribe(listener)` mutable registration API | Listeners can be added/removed after construction | Adds mutable listener-list state (itself needing thread-safety) to a class whose whole value proposition is a tight, auditable set of invariants | Keeping it immutable-after-construction keeps `LFUCache`'s state surface exactly as large as needed to prove the invariants in section 5 | A long-lived cache where listeners genuinely need to be swapped at runtime (e.g. a feature flag toggling write-back on/off) |

## 17. Production Evolution

Concerns deliberately kept **out** of the interview-scope design above, to
be layered on afterward:

- **Monitoring/metrics:** `StatsRecordingCache` already provides
  hit/miss/eviction counts in-process; production evolution means exporting
  `CacheStats` periodically to a metrics backend (e.g. Micrometer/Prometheus)
  rather than requiring callers to poll `stats()` manually.
- **Logging:** structured logging of evictions (key, frequency at eviction,
  time since last access) via an `EvictionListener` implementation — the
  extension point already exists (section 12); only the listener
  implementation is new.
- **Distributed coordination:** if multiple cache instances across machines
  must agree on eviction decisions (rare — usually each node's cache is
  independent by design for latency reasons), that is a separate
  distributed-consensus problem layered *above* this design, not a
  modification to it.
- **Persistence/write-through:** synchronous or asynchronous write-back via
  `EvictionListener`, as discussed in section 12, with its own retry/backoff
  policy for the backing store.
- **Fault tolerance:** since this is pure in-memory state, "fault
  tolerance" for the cache itself means graceful behavior on restart (cold
  cache, naturally handled — it just starts empty) rather than any
  in-process recovery mechanism; a production system typically accepts a
  cold-cache warm-up period after a restart rather than engineering around
  it.
- **Operational concerns:** capacity tuning based on observed hit rate
  (via `CacheStats.hitRate()`), alerting on sustained high eviction rate
  (a signal that capacity is undersized for the workload), and dynamic
  resizing (flagged in section 3 as a clarifying question whose answer was
  assumed "no" for this design, but whose extension point would be a new
  `resize(int)` method requiring careful handling of in-flight evictions
  under the existing lock).
- **Sharding for horizontal scale:** as discussed in section 14, splitting
  the key space across N independent cache instances to reduce lock
  contention, accepting approximate (per-shard) rather than exact (global)
  LFU ordering.
