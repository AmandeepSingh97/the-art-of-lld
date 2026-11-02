# Java Concepts Revision Guide

A consolidated refresher on the Java concurrency/collections concepts this
repo's 17 problems rely on, with runnable-style snippets and a pointer to
where each concept is actually exercised in this repo (not just theory).
Read end-to-end once before an interview loop; it's intentionally short
enough for one sitting.

---

## HashMap internals (bucket / treeify / resize)

`HashMap<K,V>` is an array of buckets (`Node<K,V>[] table`); a key's bucket
index is `(table.length - 1) & hash(key)`, where `hash()` XOR-folds the
upper bits of `key.hashCode()` into the lower bits to reduce collisions from
poor `hashCode()` implementations.

- **Collision chains.** Multiple keys landing in the same bucket form a
  linked list, traversed with `.equals()` to find the exact key.
- **Treeification.** Since Java 8, if a single bucket's chain grows to
  **8 or more** nodes *and* the table has at least 64 buckets, that bucket
  is converted from a linked list to a **red-black tree**, turning worst-case
  lookup from O(n) to O(log n) for that bucket. It converts back to a list
  if the bucket shrinks to 6 entries. This exists specifically to bound
  the damage from adversarial or pathological `hashCode()` collisions.
- **Resize.** When `size > capacity * loadFactor` (default load factor
  0.75), the table doubles in size and every entry is rehashed into the new,
  larger table — an O(n) operation that is why `HashMap`'s amortized O(1) has
  occasional O(n) spikes.

```java
// Why a bad hashCode() is a real production bug, not just style:
class BadKey {
    final int id;
    @Override public int hashCode() { return 1; } // forces every key into one bucket
}
// Every BadKey collides -> O(n) lookups (or O(log n) post-treeify) instead of O(1).
```

**Where this repo uses it:**
- **06-lfu-cache** discusses `HashMap`'s lack of thread safety and the
  resize-race failure mode explicitly in its
  [concurrency section](06-lfu-cache/README.md#11-concurrency-and-thread-safety)
  ("pre-Java-8 \[this] could corrupt the map's internal bucket array... or
  spin into an infinite loop during a resize race").
- **12-contact-management-system**'s `STAFF_LEVEL_DEEP_DIVE.md` and README
  discuss treeification directly when justifying `Contact`'s `hashCode()`
  design and `TrieNameIndex`'s lookup complexity versus a plain `HashMap`.

---

## ConcurrentHashMap (bin-level locking, compute/computeIfAbsent atomicity)

`ConcurrentHashMap` (Java 8+) does **not** use the old segment-locking model;
instead, writes lock at the level of an individual bin (bucket head node),
so writes to different buckets never contend. Reads are mostly lock-free
(`volatile` reads of bin heads).

- **`computeIfAbsent(key, fn)`** guarantees `fn` runs **at most once per key**
  even if many threads call it concurrently for the same absent key — this is
  the standard way to implement "construct exactly once, even under a race."
- **`compute`/`merge`** are similarly atomic per key: the whole
  read-modify-write for that key happens under that key's bin lock.
- **`remove(key, expectedValue)`** (inherited from `ConcurrentMap`) is an
  atomic **conditional remove** — only removes if the currently-mapped value
  equals `expectedValue` — the standard way to close a check-then-act race
  without an external lock.

```java
// Exactly-once lazy construction under concurrent callers:
ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();
Logger logger = loggers.computeIfAbsent(name, n -> new Logger(n)); // never double-constructed

// Closing a check-then-act race with conditional removal:
CacheEntry<V> expired = store.get(key);
if (expired != null && expired.isExpired()) {
    store.remove(key, expired); // only removes if nobody replaced it with a fresh value since
}
```

**Where this repo uses it:**
- **02-ttl-cache**: `store.remove(key, expectedEntry)` is the central
  mechanism closing the "reading a value that is concurrently expiring" race
  — see [§11.2](02-ttl-cache/README.md#11-concurrency-and-thread-safety).
  `LoggerFactory`-equivalent exactly-once-construction pattern appears via
  `ConcurrentHashMap.computeIfAbsent` in **05-logging-framework**'s
  `LoggerContext.loggers` map (see
  [§11.1](05-logging-framework/README.md#11-concurrency-and-thread-safety)).
- **17-google-drive**: `InMemoryBlobStore` uses `ConcurrentHashMap.compute()`
  to atomically increment/decrement blob reference counts per content hash
  — see [§11.1](17-google-drive/README.md#111-inventory-of-shared-mutable-state).

---

## synchronized vs ReentrantLock vs ReadWriteLock vs StampedLock

| Mechanism | Reentrant? | Fairness option | Interruptible acquire? | Try-with-timeout? | Best for |
|---|---|---|---|---|---|
| `synchronized` | Yes | No | No | No | Simple, single critical section, no need for advanced features |
| `ReentrantLock` | Yes | Yes (constructor flag) | Yes (`lockInterruptibly`) | Yes (`tryLock(timeout)`) | Needing `tryLock`, fairness, or interruptibility |
| `ReentrantReadWriteLock` | Yes (per mode) | Yes | Yes | Yes | Read-heavy workloads where reads can run concurrently and only writes need exclusivity |
| `StampedLock` | No | No | Partial | Yes | Read-heavy, write-light workloads needing the fastest possible read path (optimistic reads), when you can tolerate a more error-prone API |

```java
// ReentrantReadWriteLock: many readers, one writer, never both
private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
double getBalance(String userId) {
    rw.readLock().lock();
    try { return balances.get(userId); } finally { rw.readLock().unlock(); }
}
void recordDebt(String from, String to, double amount) {
    rw.writeLock().lock();
    try { /* multi-key ledger update */ } finally { rw.writeLock().unlock(); }
}
```

**Why `ReentrantReadWriteLock` over `StampedLock` is a real, justified
choice, not a default:** Splitwise's `STAFF_LEVEL_DEEP_DIVE.md` directly
answers "why not `StampedLock`, which is faster for read-heavy workloads" —
`StampedLock` isn't reentrant, has a more error-prone manual-stamp-validation
API, and the raw throughput edge doesn't matter when lock-held operations are
already microsecond-scale hash-map lookups. See
[09-splitwise/STAFF_LEVEL_DEEP_DIVE.md Q20](09-splitwise/STAFF_LEVEL_DEEP_DIVE.md).

**Where this repo uses it:**
- **07-file-management-system**: `FileSystem.treeLock` is a
  `ReentrantReadWriteLock` for structure, with a separate per-`File` lock for
  content — the two-tier model is the core of
  [§11](07-file-management-system/README.md#11-concurrency-and-thread-safety).
- **10-meeting-scheduler**: per-resource `ReentrantLock`s acquired via
  `tryLock(timeout, unit)` in a fixed sorted order — see
  [§11](10-meeting-scheduler/README.md#11-concurrency-and-thread-safety).
- **03-vending-machine**: a single coarse `ReentrantLock` (`machineLock`)
  for the front panel, with `synchronized` on `Slot` separately for admin
  restocking — see
  [§11](03-vending-machine/README.md#11-concurrency-and-thread-safety).
- **14-digital-wallet**: per-`Account` `ReentrantLock`s, acquired in a
  **consistent sorted order** for two-account transfers — see
  [§11.3](14-digital-wallet/README.md#11-concurrency-and-thread-safety).

---

## Atomic classes (AtomicInteger/AtomicLong/AtomicReference, CAS loops, ABA problem)

All `java.util.concurrent.atomic` classes are built on `compareAndSet`
(CAS) — a single hardware instruction (`LOCK CMPXCHG` on x86) that atomically
compares the current value to an expected value and, only if they match,
writes a new value. The standard idiom for a compound update is a **CAS
retry loop**:

```java
AtomicInteger occupancy = new AtomicInteger(0);
void boardPassenger() {
    occupancy.updateAndGet(current -> current + 1); // built-in CAS loop, no manual retry needed
}

// Manual CAS loop (when the update logic is more than a simple function):
AtomicReference<LockState> seatState = new AtomicReference<>(LockState.AVAILABLE);
boolean tryLock(String byWhom) {
    while (true) {
        LockState current = seatState.get();
        if (current != LockState.AVAILABLE) return false;
        LockState next = LockState.lockedBy(byWhom);
        if (seatState.compareAndSet(current, next)) return true;
        // else: someone else mutated it between our read and our CAS — retry with a fresh read
    }
}
```

**The ABA problem:** if a value changes from A → B → A between a thread's
read and its CAS, the CAS succeeds (it only compares the *current* value to
A, which matches) even though the value *did* change in between — this is
invisible to a plain CAS and can be a real bug when the "same value" doesn't
actually mean "nothing happened" (e.g., a reused object reference, or a
counter that wrapped). The standard fixes are a version-tagged value
(`AtomicStampedReference`) or, as this repo consistently does, making the
compared value an **immutable, uniquely-identified snapshot** so "the same
reference" really does mean "nothing changed" — see Contact Management's
`compareAndSet` discussion below.

**Where this repo uses it:**
- **04-bookmyshow**: `AtomicReference<LockState>` per seat is the canonical
  CAS-under-real-contention example — see
  [§11.1](04-bookmyshow/README.md#111-showseat--the-hot-path-analyzed-in-full).
- **12-contact-management-system**: explicitly avoids the ABA trap by using
  **reference-identity** comparison (`current == expected`) over immutable
  `Contact` snapshots instead of `ConcurrentHashMap.replace` (which compares
  via `.equals()`, and `Contact.equals()` is id-based, not full-value —
  which would let a lost update slip through `replace`). See
  [§11](12-contact-management-system/README.md#11-concurrency-and-thread-safety).
- **13-stock-management-system**'s `OptimisticVersionedInventoryLedger` CAS's
  a whole immutable `InventoryLevel` snapshot (including an explicit
  `version` field) per update — see
  [§11, Strategy 1](13-stock-management-system/README.md#11-concurrency-and-thread-safety).
- **15-elevator-system**: `AtomicInteger.getAndUpdate`/`updateAndGet` for
  occupancy, `AtomicLong` for tick counters — see
  [§11](15-elevator-system/README.md#11-concurrency-and-thread-safety).

---

## ExecutorService (thread pool types, shutdown semantics)

| Factory method | Pool shape | Use case |
|---|---|---|
| `newFixedThreadPool(n)` | Fixed N threads, unbounded queue | Known, bounded parallelism |
| `newCachedThreadPool()` | Grows unbounded, reuses idle threads, 60s keep-alive | Bursty, short-lived tasks |
| `newSingleThreadExecutor()` | Exactly 1 thread | Serialize tasks while keeping an async API |
| `newScheduledThreadPool(n)` | Fixed pool + delay/periodic scheduling | Background sweepers/reapers |

**Shutdown semantics matter:** `shutdown()` stops accepting new tasks but
lets queued/running tasks finish; `shutdownNow()` attempts to stop everything
immediately (interrupts running threads, drains the queue, returns undrained
tasks). Always pair with `awaitTermination(timeout, unit)` to actually wait,
and guard against being called twice concurrently.

```java
private final AtomicBoolean closed = new AtomicBoolean(false);
public void close() {
    if (closed.compareAndSet(false, true)) { // ensures exactly-once shutdown even if called concurrently
        reaperExecutor.shutdownNow();
    }
}
```

**Where this repo uses it:**
- **02-ttl-cache**: a `ScheduledExecutorService`-backed reaper thread, with
  `AtomicBoolean.compareAndSet` in `close()` guaranteeing idempotent,
  race-free shutdown even if called from two threads at once — see
  [§11.1](02-ttl-cache/README.md#11-concurrency-and-thread-safety).
- **05-logging-framework**: `AsyncAppender`'s single background consumer
  thread, with the same `AtomicBoolean` idempotent-close idiom — see
  [§11.1](05-logging-framework/README.md#11-concurrency-and-thread-safety).

---

## CompletableFuture

`CompletableFuture<T>` represents an async computation with composable
callbacks (`thenApply`, `thenCompose`, `thenCombine`, `exceptionally`),
avoiding nested callback pyramids and giving a clean way to run work off the
calling thread while still returning a value the caller can block on
(`.get()`) or chain further.

```java
CompletableFuture<Notification> notifyAsync(Message message) {
    return CompletableFuture
        .supplyAsync(() -> renderNotification(message))
        .thenApply(this::deliver)
        .exceptionally(ex -> { log.warn("delivery failed", ex); return null; });
}
```

**Where this repo discusses it:** **16-slack-chat**'s
`STAFF_LEVEL_DEEP_DIVE.md` discusses `CompletableFuture` as the production
evolution path for async notification delivery beyond the in-process
`EventBus` used in the LLD-scope implementation — see
[16-slack-chat/STAFF_LEVEL_DEEP_DIVE.md](16-slack-chat/STAFF_LEVEL_DEEP_DIVE.md).

---

## BlockingQueue implementations

| Implementation | Bounded? | Backing structure | Use case |
|---|---|---|---|
| `ArrayBlockingQueue` | Yes (fixed) | Circular array | Predictable backpressure, bounded memory |
| `LinkedBlockingQueue` | Optionally | Linked nodes | Higher throughput, optional unbounded growth |
| `PriorityBlockingQueue` | No | Heap | Priority-ordered consumption |
| `SynchronousQueue` | Zero capacity | Direct handoff | Hand off a task to a waiting consumer with no buffering |

```java
// Producer/consumer decoupling caller latency from I/O latency:
private final BlockingQueue<LogEvent> queue = new ArrayBlockingQueue<>(1024);
void append(LogEvent event) {
    if (!queue.offer(event)) { droppedEventCount.incrementAndGet(); } // non-blocking; drop under backpressure
}
// single background consumer thread:
void runConsumerLoop() {
    while (running.get()) {
        LogEvent event = queue.take(); // blocks until an event is available
        delegate.append(event);
    }
}
```

**Where this repo uses it:** **05-logging-framework**'s `AsyncAppender` uses
an `ArrayBlockingQueue` for the producer(s)-to-single-consumer handoff,
explicitly choosing `offer()` (non-blocking, drop-and-count) over `put()`
(blocking) so a slow destination never blocks application threads — see
[§11.1](05-logging-framework/README.md#11-concurrency-and-thread-safety) and
the full design rationale in
[05-logging-framework/README.md §7](05-logging-framework/README.md#7-design-patterns)
(Producer/Consumer pattern row).

---

## Other concurrent collections (ConcurrentSkipListMap/Set, CopyOnWriteArrayList, LongAdder)

- **`ConcurrentSkipListSet`/`ConcurrentSkipListMap`**: a lock-free, sorted
  concurrent collection (skip list, O(log n) operations) — use when you need
  **both** concurrent access **and** sorted iteration/nearest-match queries
  (`ceiling`, `floor`, `pollFirst`).
- **`CopyOnWriteArrayList`/`CopyOnWriteArraySet`**: every mutation copies the
  entire backing array; reads/iteration never block and never throw
  `ConcurrentModificationException` (iterators see a fixed snapshot). Ideal
  for **read-heavy, write-rare** collections (listener lists, config that
  changes rarely).
- **`LongAdder`**: a striped counter that scales better than `AtomicLong`
  under high-contention increments by writing to per-thread cells and summing
  only when a total is read — trades a larger memory footprint and slower
  reads for much faster, less-contended writes.

```java
// Lock-free "find and claim the nearest matching element":
ConcurrentSkipListSet<ParkingSpot> available = new ConcurrentSkipListSet<>(bySpotNumber);
ParkingSpot claimed = available.pollFirst(); // atomically removes AND returns in one call — removal IS the claim

// Listener list that's read on every hot-path call but written rarely:
private final List<StockEventListener> listeners = new CopyOnWriteArrayList<>();

// High-contention counter where exact real-time value isn't needed on every write:
private final LongAdder hitCount = new LongAdder();
void recordHit() { hitCount.increment(); }
long totalHits() { return hitCount.sum(); } // only pay the summation cost when actually read
```

**Where this repo uses it:**
- **01-parking-lot**: `ConcurrentSkipListSet<ParkingSpot>` per (floor, size)
  bucket, where `pollFirst()` *is* the atomic claim operation — see
  [§11.2](01-parking-lot/README.md#11-concurrency-and-thread-safety).
- **15-elevator-system**: `ConcurrentSkipListSet` for each car's
  `upStops`/`downStops` — see
  [§11](15-elevator-system/README.md#11-concurrency-and-thread-safety).
- **04-bookmyshow**: `CopyOnWriteArrayList<BookingEventListener>` for the
  observer list — see
  [§7](04-bookmyshow/README.md#7-design-patterns).
- **02-ttl-cache**: `LongAdder` inside `CacheStatsRecorder` for lock-free hit
  /miss/eviction counters under concurrent callers and the reaper thread —
  see [§11.1](02-ttl-cache/README.md#11-concurrency-and-thread-safety).

---

## equals/hashCode contract

The contract: if `a.equals(b)` is `true`, then `a.hashCode() == b.hashCode()`
**must** also be true (the reverse is not required — unequal objects *may*
share a hash code, a collision). Violating this breaks every hash-based
collection silently (an object can become "lost" in a `HashMap`/`HashSet` —
present by `.equals()` but unreachable by lookup, because it's filed under
the wrong bucket).

**The subtle trap this repo explicitly documents:** entity classes often want
**id-based equality** (two different snapshots of "the same logical entity"
should be `.equals()`), which is a deliberate, valid design — but it means
`.equals()` does **not** mean "same field values," and code that assumes
otherwise (e.g., using `map.replace(key, oldValue, newValue)`, which compares
via `.equals()`) can silently accept a stale write. See Contact Management's
CAS discussion above for the concrete fix (reference-identity comparison
instead of `.equals()`-based comparison) — see
[12-contact-management-system/README.md §11](12-contact-management-system/README.md#11-concurrency-and-thread-safety).

---

## Immutability (why/how)

An immutable object has all `final` fields, no setters, defensively-copied
mutable inputs (`List.copyOf`, not storing the caller's original list
reference), and no method that lets internal state escape mutably. The payoff
this repo leans on repeatedly: **immutable objects are trivially thread-safe
— they need zero synchronization to be safely read by any number of threads,
because there is no "mutation" to race on, only "replacement" (which a single
reference write/CAS can make atomic).**

```java
public final class CacheEntry<V> { // no setters, every field final
    private final V value;
    private final long expiryNanos;
    CacheEntry(V value, long expiryNanos) { this.value = value; this.expiryNanos = expiryNanos; }
    // "mutating" a cache entry = constructing a brand-new one and swapping the reference
}
```

**Where this repo uses it:**
- **12-contact-management-system**: `Contact` is fully immutable; "mutation"
  is always "construct a new `Contact`, then `compareAndSet` the reference" —
  the single design decision the README says "is what makes most of the rest
  of the concurrency story simple instead of defensive." See
  [§11](12-contact-management-system/README.md#11-concurrency-and-thread-safety).
- **02-ttl-cache**: `CacheEntry` is immutable specifically to avoid a
  "torn read" where one thread could observe a new expiry paired with an old
  value — see
  [§7](02-ttl-cache/README.md#7-design-patterns).
- **11-snake-and-ladder**: `Board` is immutable after `BoardBuilder.build()`
  (`Map.copyOf`), making it safely shareable across many concurrently-running
  `Game` instances with zero synchronization — see
  [§11](11-snake-and-ladder/README.md#11-concurrency-and-thread-safety).

---

## Interfaces vs abstract classes

| | Interface | Abstract class |
|---|---|---|
| Multiple inheritance | Yes (a class can implement many) | No (single superclass only) |
| State (instance fields) | No (only constants) | Yes |
| Shared implementation | Only via `default` methods | Yes, freely |
| Use when | Defining a *capability/contract* multiple unrelated classes can satisfy | Sharing *implementation* among closely related subclasses, with some abstract hooks |

This repo's pattern: nearly every Strategy/Repository/Observer abstraction is
an **interface** (no shared state to inherit, multiple unrelated
implementations expected) — e.g., `PricingStrategy`, `SplitStrategy`,
`CacheEvictionListener`. The one deliberate **abstract class** use is
**05-logging-framework**'s `AbstractAppender`, which uses **Template Method**:
it implements the *shared* gating logic (threshold check → filter chain →
layout formatting) as a `final` method and leaves only `doAppend(String,
LogEvent)` abstract for subclasses — because that shared logic is real
inherited *implementation*, not just a contract. See
[05-logging-framework/README.md §7](05-logging-framework/README.md#7-design-patterns).

---

## Java Memory Model (happens-before, volatile, visibility vs atomicity)

Without synchronization, the JMM makes **no guarantee** that a write by
thread A is ever visible to thread B — not "probably slow," but legally
never, because of compiler/CPU reordering and per-core caching. A
**happens-before** edge (established by `volatile` reads/writes, lock
acquire/release, thread start/join, and a few others) is what guarantees
visibility and ordering across threads.

- **`volatile`** guarantees **visibility** (every read sees the latest
  write) and prohibits reordering around it, but does **not** guarantee
  **atomicity** of compound operations (`volatile int x; x++;` is still a
  read-modify-write race between threads).
- **Visibility vs. atomicity, as a concrete distinction this repo draws
  explicitly:** Snake and Ladder's `Player.position` is `volatile`
  specifically to give a narrow, correctly-scoped guarantee — safe
  *publication* of the latest value to a concurrent *reader* (e.g. a
  dashboard thread) — while explicitly **not** claiming it makes concurrent
  *mutation* (`moveTo()` from two threads) safe, because that's out of the
  documented single-writer contract. See
  [§11](11-snake-and-ladder/README.md#11-concurrency-and-thread-safety).

```java
private volatile int position; // visibility only, not compound-operation atomicity
void moveTo(int newPosition) { this.position = newPosition; } // single writer, by contract
int currentPosition() { return position; } // any thread sees the latest write, no lock needed
```

**Where this repo uses it:**
- **15-elevator-system**: `currentFloor`/`state` are `volatile` for
  cross-thread visibility, combined with `synchronized` methods as a
  defensive single-writer guard on top — see
  [§11](15-elevator-system/README.md#11-concurrency-and-thread-safety).
- **05-logging-framework**: `Logger.level` is an `AtomicReference` (not
  plain `volatile`) specifically because a `LogLevel` reassignment must be
  seen as one consistent, non-torn value — functionally similar to
  `volatile` for a single reference, but chosen for API symmetry with other
  atomic fields in the class.

---

## Thread safety documentation conventions (how to state guarantees precisely)

Every README in this repo states thread-safety guarantees **precisely**,
never just "this class is thread-safe." The recurring template, visible in
every §11 section:

1. **Name the exact shared mutable field(s).**
2. **State the race that would occur without protection** (be concrete: "two
   threads read X, both decide Y, both write Z").
3. **Name the exact mechanism** (not "synchronization" — `AtomicReference`
   CAS, `ReentrantReadWriteLock`, immutability, etc.).
4. **State the granularity** (per-object? per-key? one global lock?) and
   justify it against the actual contention pattern.
5. **State what is *not* covered** — e.g., Snake and Ladder explicitly says
   `volatile` does *not* make concurrent mutation safe, only safe
   publication; Vending Machine explicitly says the front-panel lock does
   *not* protect against a fleet-of-machines scenario, which is out of scope.

This "precise, bounded claim" convention is what separates a Staff-level
thread-safety write-up from "I used a `ConcurrentHashMap` so it's safe" —
every one of this repo's 17 problems is held to it.

---

## Exception handling (checked vs unchecked, when to use which)

- **Checked exceptions** (`extends Exception`) force callers to handle or
  declare them — appropriate for **recoverable, expected** conditions a
  caller should be forced to consider (e.g., `IOException` from a file read).
- **Unchecked exceptions** (`extends RuntimeException`) don't force
  handling — appropriate for **programming errors or domain-rule violations**
  where forcing every caller to catch them would be noise (most call sites
  can't meaningfully recover; they should propagate to a top-level handler).

This repo's consistent convention (called out explicitly in several
READMEs' "API and Method Design" sections, e.g. Parking Lot's): **domain
exceptions are unchecked**, all extending one root exception per problem
(e.g. `ParkingLotException`), because a caller of `parkVehicle()` forgetting
to catch `SpotNotAvailableException` shouldn't fail to compile — it should
be a clear, named runtime exception that a service-layer boundary translates
into an HTTP status code or similar, without forcing every intermediate
caller to declare `throws` for a condition they can't locally fix anyway.

```java
public class ParkingLotException extends RuntimeException {
    public ParkingLotException(String message) { super(message); }
}
public final class SpotNotAvailableException extends ParkingLotException { /* ... */ }
```

**Where this repo discusses it:** every problem's README §8 ("API and Method
Design") documents its exception hierarchy as unchecked-rooted-at-one-base,
e.g.
[01-parking-lot/README.md §8](01-parking-lot/README.md#8-api-and-method-design)
("Exceptions (all unchecked, all extend `ParkingLotException`)").
