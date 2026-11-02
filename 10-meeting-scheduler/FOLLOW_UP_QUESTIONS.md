# Follow-Up Questions

A bank of realistic interviewer follow-up questions, organized by category, each with a full
answer grounded in this specific implementation (not generic textbook definitions). Minimum 25
questions across all categories; several categories exceed the minimum where the problem domain
warrants it.

---

## Object-Oriented Design

**1. Why is `Meeting` mutable (status, exceptionDates) while `TimeInterval` and `RecurrenceRule` are fully immutable?**
`TimeInterval` and `RecurrenceRule` are value objects — they represent a fixed fact ("this span
of time," "this repeating pattern") that, once created, never needs to change; if you want a
different interval you simply construct a new one. `Meeting` is an entity with a lifecycle —
its identity (`MeetingId`) persists across state transitions (scheduled → cancelled) and across
exception dates being added when individual occurrences are cancelled from a series. Modeling
entity lifecycle as mutation (with `volatile`/thread-safe collections for the mutable fields) is
appropriate; modeling it as "replace the whole aggregate with a new immutable copy" would work
too (and is arguably more defensive) but adds allocation overhead and object-identity confusion
for something with persistent identity, so mutation with explicit thread-safety primitives was
chosen instead.

**2. Why does `BookingRequest` use a Builder instead of a telescoping constructor or a record?**
`BookingRequest` has 3 required fields and 4 optional ones (recurrence rule, specific attendee
set beyond organizer, etc.) — a plain constructor would either need multiple overloads
(telescoping, error-prone, hard to read at call sites: `new BookingRequest(title, org, room,
start, end, null, null, Set.of())` is unreadable) or force every caller to pass nulls/defaults
explicitly. The Builder makes optional fields genuinely optional and self-documenting at the
call site (`builder.withRecurrence(rule)`), and validates invariants (organizer auto-added) at
`build()` time in one place. A record would need a canonical constructor just as unwieldy as the
telescoping option, with no fluent optional-field ergonomics.

**3. Is `Room` an aggregate root, or part of the `Meeting` aggregate?**
`Room` is its own aggregate root — it has independent identity, lifecycle, and invariants
(capacity, equipment) that don't depend on any particular meeting, and multiple meetings
reference the same room without the room "belonging" to any of them. `Meeting` references
`Room` only by `RoomId` (not by object reference), which is the standard DDD signal that they're
separate aggregates — an aggregate should only hold direct references to objects inside its own
boundary, and reference other aggregates by ID.

**4. Why does `MeetingOccurrence` exist as a separate type from `Meeting`, when a `Meeting` without recurrence is "just one occurrence"?**
Because the thing actually indexed inside an `IntervalCalendar` is a *concrete time slot*, and a
single `Meeting` (with recurrence) maps to *many* concrete time slots. Conflating them would
force `IntervalCalendar` to understand recurrence rules just to know which concrete intervals a
`Meeting` occupies — a clear separation-of-concerns violation (the calendar shouldn't need to
know *why* a slot is booked, just *that* it is). `MeetingOccurrence` is the thin, calendar-facing
projection (`MeetingId` + one concrete `TimeInterval`); `Meeting` is the full domain aggregate
(organizer, attendees, recurrence rule, status) that recurrence expansion consumes to produce
occurrences.

**5. Why are `UserId`/`RoomId`/`MeetingId` distinct types instead of all just being `String` or `UUID`?**
To get compile-time protection against passing the wrong kind of ID to the wrong method — e.g.
`repository.findById(UserId id)` simply cannot accept a `RoomId` by mistake, a class of bug
("primitive obsession") that's otherwise easy to introduce and hard to catch via tests alone
(a swapped-argument bug compiles fine with raw `String`s and only fails at runtime, if at all).
The cost (a thin wrapper record per ID type) is trivial compared to the benefit for a system
with this many distinct identity types in play simultaneously.

---

## SOLID Principles

**6. Where's the clearest Single Responsibility Principle boundary in this design?**
`IntervalCalendar` (is a slot free, and track what's booked) vs. `RecurrenceExpansionService`
(what concrete dates does this rule produce) vs. `ResourceLockCoordinator` (how do I safely
coordinate concurrent access) vs. `FreeSlotFinder` (given busy intervals, where are the gaps).
Each has exactly one axis of change: the conflict-detection data structure can change (TreeMap →
interval tree) without touching recurrence logic; the recurrence algorithm can change
(add monthly) without touching locking; the locking strategy can change (per-resource locks →
optimistic versioning) without touching free-slot search.

**7. Which class in this design is most at risk of becoming a God Object, and how do you prevent that?**
`SchedulerService` — it's the facade, so it's naturally the place new orchestration logic gets
added, and facades are exactly where responsibility creep happens over time ("just add one more
method here, it's already the entry point"). The discipline that prevents this: `SchedulerService`
itself must contain almost no *logic* — it only orchestrates calls to `ResourceLockCoordinator`,
`IntervalCalendar`, `RecurrenceExpansionService`, and `FreeSlotFinder`, delegating every
non-trivial algorithm to one of those. If a method inside `SchedulerService` starts containing
real algorithmic logic (loops with non-trivial conditionals, data-structure manipulation) rather
than sequencing calls to other services, that's the signal to extract a new collaborator.

**8. Give an Open/Closed Principle example that's concrete to this codebase, not generic.**
Adding a `MONTHLY` frequency: today, `RecurrenceExpansionService` dispatches via a `switch` on
`Frequency`, delegating to a `RecurrenceExpansionStrategy` implementation. Adding monthly support
means writing a new `MonthlyExpansionStrategy` implementing the existing interface, registering it
in the dispatch map, and adding `MONTHLY` to the `Frequency` enum — zero changes to
`DailyExpansionStrategy`, `WeeklyExpansionStrategy`, `SchedulerService`, `IntervalCalendar`, or
any existing test. That's the Strategy pattern doing its job: extension without modification of
existing, already-tested code.

**9. Where does this design intentionally violate, or at least bend, Liskov Substitution — if anywhere?**
Nowhere deliberately — `IntervalCalendar`'s two implementations (`TreeMapIntervalCalendar`,
`AugmentedIntervalTree`) are substitutable for each other in terms of the conflict-detection
contract. The one place worth scrutinizing: `AugmentedIntervalTree` doesn't actually implement
`IntervalCalendar` (it has a different, richer interface supporting multi-overlap queries) —
that's deliberate, because forcing it into the same narrow interface as
`TreeMapIntervalCalendar` would either dumb down `AugmentedIntervalTree`'s genuinely different
capability (finding *all* overlaps, not just "does one exist") or require widening
`IntervalCalendar`'s contract for every implementation, including the simple one that doesn't
need it. Not implementing a shared interface at all was the more honest choice than
implementing it badly.

**10. Which part of the design best demonstrates Dependency Inversion?**
`SchedulerService` depends on the `UserRepository`/`RoomRepository`/`MeetingRepository`
*interfaces*, not the `InMemory*` implementations — constructed via dependency injection in its
constructor. This is what makes swapping in a JDBC-backed or JPA-backed repository (§12's
production evolution path) a drop-in replacement with zero changes to `SchedulerService` or any
of its tests (which use the in-memory implementations directly, since they're simple enough to
serve as fine test doubles without needing mocks).

---

## Design Patterns

**11. You use Strategy for recurrence expansion. Why not a simple `if/else` chain in `RecurrenceExpansionService` instead — isn't that simpler for just two cases?**
For exactly two cases, an `if/else` would arguably be *fewer lines*, and that trade-off is fair
to acknowledge. The reason Strategy was chosen anyway: the master spec explicitly calls for
RRULE-style expansion to be extensible (the problem statement itself says "at least
daily/weekly," implying more will follow), and each strategy's expansion logic (especially
weekly, with day-of-week sets and step intervals) is non-trivial enough to deserve isolated,
independently-testable units rather than being inlined into a branch of a larger method. If this
were truly a fixed, permanent two-case switch with no expectation of growth, a simpler
conditional would be the more honest YAGNI-respecting choice.

**12. Why Facade (`SchedulerService`) instead of exposing `IntervalCalendar`, `ResourceLockCoordinator`, etc. directly to callers?**
Because correct usage of this system requires calling several collaborators *in a specific
coordinated sequence* (acquire locks → validate all occurrences against all resources → commit
all → notify listeners) — exposing the pieces individually would require every caller to
re-implement that orchestration correctly, including the subtle all-or-nothing and
deadlock-avoidance guarantees. The Facade exists precisely to make "the only way to book a
meeting" also "the only correct way to book a meeting," collapsing a sequence of fallible steps
into one atomic-looking call.

**13. Where's the Observer pattern used, and why not just have `SchedulerService` call downstream systems (e.g. notification service) directly?**
`BookingEventListener`/`ConsoleBookingEventListener`, registered via `SchedulerService.addListener`.
Direct calls would couple `SchedulerService` to every downstream concern it needs to notify
(email, push notification, audit log, analytics) — each new notification channel would require
modifying `SchedulerService` itself. Observer inverts that: `SchedulerService` just emits "a
meeting was booked/cancelled" events, and any number of listeners can subscribe without
`SchedulerService` knowing or caring how many, or what they do with the event.

**14. Why not use the Command pattern for booking/cancellation operations, given this looks like a natural fit for undo/redo?**
Command would be a strong fit *if* undo/redo were an actual requirement — it isn't, per the
spec's scope, and introducing it preemptively would be speculative generality. If a future
requirement like "undo my last 5 scheduling actions" emerged, wrapping `bookMeeting`/
`cancelMeeting` calls in `Command` objects with captured pre-state would be the natural
extension — but building that scaffolding now, with no concrete need, is exactly the kind of
premature abstraction worth resisting and naming as a deliberate scope decision rather than an
oversight.

**15. Could the Builder on `BookingRequest` have instead been a Factory Method on `SchedulerService` (e.g. `schedulerService.bookOneOff(...)`, `schedulerService.bookRecurring(...)`)?**
Yes, and it's a legitimate alternative — factory methods on the service would avoid exposing a
separate builder type entirely. The trade-off: as optional parameters grow (which this problem
domain does — room, specific attendees beyond organizer, recurrence rule), factory methods
multiply combinatorially (`bookOneOffWithRoom`, `bookOneOffWithoutRoom`,
`bookRecurringWithRoom`, ...) or collapse back into long parameter lists, reintroducing the
original problem. The Builder keeps `SchedulerService`'s public surface to a single
`bookMeeting(BookingRequest)` method regardless of how many optional fields `BookingRequest`
grows to have.

---

## Data Structures

**16. Why `TreeMap` and not a plain sorted `ArrayList` with binary search for the conflict check?**
Both give O(log n) *lookup*, but insertion and deletion differ sharply: a sorted `ArrayList`
requires O(n) element shifting on every insert/remove (to keep the array sorted and contiguous),
while `TreeMap`'s red-black tree gives O(log n) insert/delete as well as O(log n) lookup. Since
bookings are inserted and cancelled continuously (not built once and queried read-only), the
O(n) insertion cost of a sorted array would dominate at scale — a 10,000-booking resource would
cost ~5,000 average element shifts per single new booking with an array, versus ~13 comparisons
with `TreeMap`.

**17. Why not a hash-based structure (`HashMap`) keyed by start time instead of `TreeMap`?**
`HashMap` gives O(1) exact-key lookup but no notion of ordering — and the entire conflict-check
algorithm depends on `floorEntry`/`ceilingEntry`, i.e. "find the nearest keys below/above an
arbitrary query point," which is fundamentally a *sorted-order* operation with no hash-based
equivalent. `HashMap` would force falling back to a full scan for conflict detection, losing the
O(log n) guarantee entirely.

**18. Explain precisely why only the immediate floor/ceiling neighbours need checking, not all earlier/later entries.**
Because existing bookings on one resource are maintained as *pairwise non-overlapping* by this
same conflict-check being enforced on every insertion — so the set of existing bookings, sorted
by start time, forms a strictly increasing sequence of disjoint half-open intervals, each one's
end ≤ the next one's start. A candidate interval `[s, e)` can only overlap an existing interval
if that existing interval's start is < `e` AND its end is > `s`. Given the disjoint, sorted
structure, the closest candidate to violating that on the "before" side is the entry with the
largest start ≤ `s` (the floor), and on the "after" side, the entry with the smallest start ≥
`s` (the ceiling) — any entry *further* from `s` than these two is, by the disjointness
invariant, guaranteed to already be separated from its own neighbour by a non-overlap gap, which
transitively means it's separated from the candidate too. This is proven by induction over the
sorted sequence and is why checking exactly 2 entries suffices regardless of how many total
bookings exist.

**19. What's the actual time and space complexity of `AugmentedIntervalTree.findAllOverlapping`, and when would you reach for it over `TreeMapIntervalCalendar`?**
O(log n + k) time where k is the number of overlapping intervals returned (subtree pruning via
the max-end augmentation avoids descending into subtrees that provably can't contain an overlap),
O(n) space for the tree itself. You'd reach for it the moment a single resource needs to support
genuinely overlapping bookings by design — e.g. a shared open-plan desk resource that can be
"reserved" by multiple people simultaneously up to some capacity, or a resource type where the
business rule is "find every meeting that overlaps this window" (for a reporting/audit query)
rather than "is there a conflict" (a yes/no question `TreeMapIntervalCalendar` is purpose-built
and more efficient for).

**20. Why `CopyOnWriteArraySet` for `Meeting.exceptionDates` instead of a synchronized `HashSet`?**
Exception dates are read far more often than written (checked on every recurrence-expansion
call to filter out cancelled occurrences, but written only when a single occurrence is
cancelled — a comparatively rare event). `CopyOnWriteArraySet` optimizes exactly for this
read-heavy, write-light pattern: reads require no locking at all (iterating a stable snapshot
array), while writes pay a copy cost proportional to current size — a good trade given how
infrequent writes are and how small this set typically stays (bounded by the number of
cancelled occurrences in one series, not total occurrences).

---

## Java Internals

**21. Why is `Meeting.status` declared `volatile` instead of being guarded by a lock?**
`status` is a single reference field read and written independently of the other locked
operations — a plain field write might not be visible to other threads promptly due to each
thread's local caching under the Java Memory Model, even absent a data race in the traditional
sense (no two threads write it concurrently in this design, since all writes happen inside the
resource lock). `volatile` guarantees happens-before ordering for this specific field's reads and
writes across threads without requiring every reader to acquire the full resource lock just to
check status — cheaper than lock-guarding a field that's read far more often than it's written.

**22. Why do records (`TimeInterval`, `MeetingOccurrence`, `BookingResult`) make sense here, and where did you deliberately choose a class instead?**
Records are appropriate for `TimeInterval`/`MeetingOccurrence`/`BookingResult`/`UserId`/`RoomId`/
`MeetingId` because they're pure data carriers with value semantics — equals/hashCode/toString
by field value is exactly what's wanted, and immutability is free. `User`, `Room`, and `Meeting`
are deliberately classes (not records) because they have *identity* semantics, not value
semantics — two `User` objects with identical names but different IDs are different users, and
`Meeting` additionally needs genuine mutable state (status), which records (implicitly final
fields) cannot provide.

**23. What Java Memory Model guarantee does `ConcurrentHashMap.computeIfAbsent` give you that a `synchronized` block around a plain `HashMap.get`/`put` pair would not, in `SchedulerService.calendarForRoom`?**
`computeIfAbsent` is atomic — the check-then-act (is there already a calendar for this room; if
not, create and store one) happens as a single indivisible operation, so two threads racing to
get the "first" calendar for a brand-new room can never both create separate `TreeMapIntervalCalendar`
instances and have one silently overwrite the other (losing bookings already made against the
discarded instance). A naive `get` then `put` under even a correctly-placed `synchronized` block
around *just that map* would actually also be correct if the lock scope is right — but
`computeIfAbsent` makes the atomicity a structural property of the call itself rather than
something that depends on every caller remembering to wrap both the check and the act under the
same lock.

**24. Why `Instant` and not `LocalDateTime` as the type stored inside `TimeInterval`?**
`Instant` represents an unambiguous point on the global timeline (UTC-equivalent,
timezone-independent) — exactly what's needed to compare two meetings' times correctly
regardless of which timezone each was originally expressed in. `LocalDateTime` has no timezone
attached at all, so "2025-06-02T10:00" in New York and "2025-06-02T10:00" in Tokyo would compare
as *equal* under `LocalDateTime`, even though they're actually 13 hours apart in absolute time —
exactly the kind of bug this design's §15 (timezone handling) is built to avoid. `ZonedDateTime`
is used only at the presentation/input edges (`TimeZoneUtil`), converting to/from `Instant`
immediately, so the core domain never juggles timezone-aware types internally.

---

## Multithreading

**25. Explain, precisely, why sorting lock keys before acquisition prevents deadlock — not just "it does," the actual argument.**
Deadlock requires a cycle in the "waits-for" graph: thread A holds lock 1 and waits for lock 2,
while thread B holds lock 2 and waits for lock 1. If every thread acquires locks strictly in
ascending sorted order, no thread can ever be holding a "later" lock while waiting for an
"earlier" one — because by the time it would attempt to acquire the earlier lock, sorted-order
acquisition guarantees it must have already acquired that earlier lock *first*. This structurally
rules out the "holds X, waits for Y" / "holds Y, waits for X" pattern needed to form a cycle,
for *any* number of threads and *any* number of contested locks — not just the two-thread case.

**26. If `tryLock` times out partway through acquiring 4 of 6 needed locks, what happens to the 4 already held?**
`ResourceLockCoordinator.withLocks` releases every successfully acquired lock, in reverse
acquisition order, inside a `finally` block, before propagating the `SchedulingTimeoutException`
— no lock is ever left held after a failed acquisition attempt. Reverse-order release isn't
strictly required for correctness here (release order doesn't affect deadlock freedom the way
acquisition order does), but it's a defensive convention that mirrors stack-based resource
cleanup and makes the code easier to reason about.

**27. Why `tryLock(timeout)` and not just `lock()` (block indefinitely)?**
Blocking indefinitely risks an unresponsive system under heavy contention or (in the event of an
actual bug) real deadlock — a timed `tryLock` turns "the system hangs forever" into "the caller
gets a clear, typed exception (`SchedulingTimeoutException`) after a bounded wait," which is
vastly preferable operationally (the caller/client can retry, back off, or surface a clear error
to the user, instead of a request hanging with no feedback).

**28. Could you achieve the same double-booking prevention with `synchronized(this)` on `SchedulerService` itself, serializing *all* bookings globally regardless of resource?**
Yes, correctness-wise — a single global lock trivially prevents any double-booking, since it
prevents *any* concurrent booking at all. The cost is catastrophic throughput loss: booking a
meeting for Room A in New York would needlessly block booking an unrelated meeting for Room B
in Tokyo, even though they share no resources and could safely proceed in parallel. Per-resource
locking is strictly more scalable while providing the identical correctness guarantee for the
invariant that actually matters (no two overlapping bookings on the *same* resource).

---

## Database and Persistence

**29. Why in-memory repositories for this exercise, and what's the very first integration test you'd write before swapping in a real database?**
In-memory repositories keep the exercise focused on the DSA/concurrency/recurrence design that's
the actual point of the problem, while still being structured (behind interfaces) so swapping in
a real persistence layer is a contained change (§12 of the README details the schema and
exclusion-constraint approach). The first integration test against a real database: two
concurrent transactions attempting to insert overlapping bookings for the same room, asserting
exactly one succeeds and the other receives a constraint-violation error — the database-level
equivalent of `SchedulerConcurrencyTest`, proving the exclusion constraint actually enforces the
invariant under real concurrent load, not just in isolated unit tests.

**30. What database feature does the production design lean on to get double-booking prevention "for free," and why is that preferable to replicating `ResourceLockCoordinator`'s logic in SQL application code?**
PostgreSQL's `EXCLUDE` constraint using the `btree_gist` extension, with a `tsrange` column and
an overlap operator (`&&`) — the database itself rejects any insert/update that would create an
overlapping range for the same resource, atomically, as part of the transaction, with no
application-level locking required at all. This is preferable to hand-rolling equivalent logic
in application code because the database's MVCC and constraint-checking machinery is
battle-tested, handles multi-process/multi-instance concurrency (which `ResourceLockCoordinator`
explicitly cannot, being scoped to one JVM) for free, and moves the correctness-critical
invariant to the one place guaranteed to see every write, regardless of how many application
instances exist.

---

## Performance

**31. What's the dominant cost in `findCommonFreeSlots` for a very wide search window (e.g. "find free slots in the next year") with few attendees?**
Not the merge-intervals algorithm itself (still O(n log n) in the number of busy intervals
returned) — it's the *volume* of occurrences a recurring meeting could expand to across a
year-long window combined with how many attendees' calendars must be queried. The practical
mitigation is bounding the search window explicitly in the API (the design already takes a
`TimeInterval searchWindow` parameter rather than defaulting to "all time") and recommending
clients request a reasonably narrow window (a few weeks) rather than a full year, which is also
simply what's useful for a human picking a meeting time.

**32. Where would caching help most, and where would it actively hurt correctness if done carelessly?**
Caching is most valuable for read-heavy, rarely-changing data — e.g. `Room` metadata (capacity,
equipment), which changes far less often than bookings occur. Caching would actively hurt
correctness if applied naively to the `IntervalCalendar` contents themselves (the actual booked
intervals) — a stale cache of "what's booked on Room A" could let a conflict slip through if
the cache isn't invalidated precisely at the same atomic point the real booking commits, which
is exactly the kind of subtle bug class (cache incoherence racing with the source of truth) that
makes caching the conflict-detection data structure itself a bad idea without very careful
write-through/invalidation design.

---

## Failure Handling

**33. What happens if `RoomRepository.findById` throws (e.g. simulating a flaky dependency) partway through `bookMeeting`'s validation phase?**
If it throws during Phase 1 (validation, before any locks are even acquired or after acquisition
but before any mutation), the exception propagates out of `bookMeeting` uncaught, no locks are
leaked (they're acquired via `withLocks`'s try/finally, which releases on any exception, not just
`SchedulingTimeoutException`), and no partial state is committed — a clean failure. This is a
direct benefit of strict phase separation: failures during the read-only validation phase can
never leave the system in an inconsistent state, because nothing has been written yet.

**34. Your design doesn't currently handle the scheduler process crashing mid-operation durably — is that acceptable for this exercise?**
Yes, explicitly, because the chosen persistence model (in-memory) makes "durable recovery after
a crash" an out-of-scope, moot question — there's no state to recover, the whole system restarts
empty. This is clearly flagged in the README's persistence section as the reason a real
production deployment would use a database with transactional guarantees (§12) specifically to
get crash-safety, which in-memory storage cannot provide no matter how carefully the application
logic is written.

---

## Production Architecture

**35. How would you evolve this single-service design into a distributed system without rewriting the DSA core?**
The `IntervalCalendar` interface and `TreeMap`-based conflict-check logic survive almost
unchanged — they'd move from "the source of truth" to "an optional in-memory cache/read-model
per shard," with the database's exclusion constraint becoming the actual source of truth for the
double-booking invariant. The service would be sharded by resource (room/user) so that all
operations touching a given resource are routed to the same shard, preserving the
"operations on one resource are serialized" property that currently comes from in-process
locking — in a sharded world, it instead comes from routing plus the database constraint.
Recurrence expansion and free-slot search are pure, stateless computations that parallelize
trivially across any number of service instances with no coordination needed at all.

**36. What's the first operational metric you'd want visibility into after shipping this to production?**
p99 latency of `bookMeeting` specifically for the *lock-wait* portion versus the *compute*
portion — if lock-wait time starts dominating, that's the earliest possible signal that a
specific resource (likely a popular conference room) is becoming a contention hotspot, well
before users start reporting "booking is slow" anecdotally. A close second: the rate of
`MeetingConflictException` vs. `SchedulingTimeoutException` — a rising timeout rate specifically
(as opposed to conflict rate) is the signal that contention, not just legitimate double-booking
attempts, is becoming a real problem worth capacity planning for.
