# Meeting Scheduler (Calendar / Room Booking System) — Staff-Level LLD

> Java 17+. Package: `lld.meetingscheduler`. Source: `src/main/java/lld/meetingscheduler`.
> Tests: `tests/lld/meetingscheduler` (JUnit 5). Diagrams: `diagrams/*.md` (Mermaid).

---

## 1. Problem Understanding

**Plain explanation.** Design a system where users can book meetings in physical or virtual
rooms (and/or with other people) for a specific time range, without ever double-booking a room
or a person. The system must support recurring meetings (e.g. "every Monday and Wednesday at
2pm"), let a user find a time slot when a whole group of people are simultaneously free, and
behave correctly when many clients try to book the same room at the same instant.

**Real-world use case.** This is, in miniature, Google Calendar / Outlook Calendar's room- and
resource-booking subsystem, or an internal tool like Calendly/Doodle's "find a mutual slot"
feature combined with a conference-room booking system (the kind of internal tool every
mid-size-or-larger company builds).

**What the interviewer is evaluating.**
- Can you model a non-trivial domain (users, resources, recurring events, time) cleanly without
  over- or under-engineering it?
- Can you pick (and *justify*, with real complexity analysis) an efficient data structure for
  "does this new interval conflict with anything already booked?" rather than reaching for a
  naive O(n) scan?
- Do you understand *why* naive timestamp/ZonedDateTime storage causes real production bugs
  (DST drift), and design around it?
- Can you reason concretely about concurrency: what are the race conditions, how do you prevent
  double-booking under real concurrent load, and can you prove it (not just assert it)?
- Do you know when a pattern (Strategy, Builder, Observer, Facade) earns its complexity, and
  can you say no to patterns that don't?

**In scope.**
- Users, rooms (a generic bookable resource), one-off and recurring meetings.
- Conflict detection scoped to "a single resource can only be booked once for any given
  instant" (no double-booking of a room or a person).
- Daily/weekly recurrence expansion (RRULE-inspired, not a full RFC 5545 implementation).
- Finding common free slots across N attendees (and optionally a room).
- Concurrency-safe booking (no double-booking under concurrent requests) and basic idempotency.
- Timezone-correct storage and display.

**Out of scope** (called out explicitly, not silently ignored).
- Full RFC 5545 RRULE grammar (MONTHLY/YEARLY, BYSETPOS, BYMONTHDAY, negative offsets, etc.).
- Meeting invitations/RSVP workflow, free/busy privacy levels, delegate calendars.
- A real notification channel (email/push) — only the `Observer` seam is implemented.
- Distributed/multi-node deployment (sharding calendars across machines, cross-datacenter
  replication) — discussed conceptually in §14 and §17, not implemented.
- A persistent database — discussed and justified as unnecessary for this exercise in §12.

---

## 2. Requirement Gathering

### Functional — essential (interview-necessary)
1. Create a user and a room.
2. Book a one-off meeting with a title, organizer, attendees, optional room, start/end time.
3. Reject a booking if the room OR any attendee is already busy during the requested time
   (no double-booking).
4. Book a recurring meeting (daily / weekly, bounded by an end date or a occurrence count).
5. Cancel a meeting: a single occurrence, "this and following," or the whole series.
6. Find every common free slot (of at least some minimum duration) across a set of attendees
   (optionally also constrained to a specific room) within a time window.
7. Correctly handle timezones: two users in different zones booking/viewing the same meeting
   must see consistent, correct local times.

### Functional — optional / nice-to-have (explicitly deferred, noted as production extensions)
- Recurring monthly/yearly patterns, BYSETPOS-style "the 2nd Tuesday of every month."
- Meeting room suggestions ("here are 3 free rooms for your 9 attendees").
- Partial-attendance / optional-attendee semantics (meeting is "free" if only optional
  attendees conflict).
- Waitlisting for a popular room.

### Non-functional
| Concern | Requirement | Where addressed |
|---|---|---|
| Performance | Conflict check must not degrade linearly with history size | §10 (TreeMap, O(log n)) |
| Scalability | Must handle a resource with thousands of bookings without full scans | §10, §14 |
| Availability | A single slow/contended resource must not block unrelated bookings | §11 (per-resource locks) |
| Consistency | No two overlapping bookings on the same resource, ever, under concurrency | §11 |
| Extensibility | New recurrence frequency, new notification channel addable without touching existing code | §7, §9 |
| Reliability | Partial failure must not leave a resource half-booked | §11, §13 |
| Thread-safety | Explicit, justified, testable (not asserted) | §11 |

### Interview-necessary vs. production-extension split
This README implements every "essential" functional requirement and every non-functional
requirement **at single-process scale**. Distributed coordination (multiple scheduler
processes), durable persistence, metrics/observability, and a full RRULE grammar are explicitly
flagged as **Production Evolution** (§17) rather than silently assumed or silently omitted.

---

## 3. Clarifying Questions

Below are the questions a staff engineer would actually ask before designing this, why each one
matters, and the assumption this solution makes when the question goes unanswered (as it would
in most interviews, where you must state an assumption and move on).

1. **Can a room have a capacity constraint that allows multiple simultaneous bookings (e.g. an
   open-seating area for 20 people, booked in groups of 4)?**
   *Why it matters:* this is the single biggest fork in the design. If a resource can have
   legitimately overlapping bookings, the "one booking at a time" invariant that makes the
   TreeMap-neighbour-check trick work (§10) no longer holds, and a true interval tree /
   segment-tree-with-capacity-counting structure is required.
   *Assumption:* **no** — each room/attendee is single-occupancy (one meeting at a time), which
   is true for the overwhelming majority of real conference-room booking systems. The
   `AugmentedIntervalTree` class is included specifically to show what changes if this
   assumption is ever relaxed.

2. **Do we need to support recurrence patterns beyond daily/weekly (monthly, yearly, "last
   business day of the month")?**
   *Why it matters:* a full RFC 5545 RRULE engine is a multi-week project on its own.
   *Assumption:* daily/weekly only, as stated in the problem scope; the `Frequency` enum and
   `RecurrenceExpansionStrategy` interface are deliberately open for extension (§7).

3. **Is an attendee's calendar "global" (their busy time is the same seen from every meeting
   they're in), or can different meetings have different notions of "this attendee is free"
   (e.g. tentative vs. confirmed)?**
   *Why it matters:* determines whether one `IntervalCalendar` per user suffices, or whether we
   need a richer per-booking status model.
   *Assumption:* a single global calendar per user; every booked occurrence is a hard
   commitment (no tentative/confirmed states) — simplest model that satisfies the stated
   requirements.

4. **What happens when a recurring meeting is being booked and only *some* of the occurrences
   conflict — do we book the non-conflicting ones and skip the rest, or reject the whole
   series?**
   *Why it matters:* this is a real, user-visible product decision (compare: Google Calendar
   lets you review and resolve conflicts per-occurrence interactively).
   *Assumption:* **all-or-nothing** (reject the whole series on any single conflicting
   occurrence) — simpler to reason about and implement correctly under concurrency, and avoids
   silently creating a "sparse" recurring series the organizer didn't ask for. Documented as a
   deliberate trade-off in §16.

5. **How many attendees does a typical/maximum meeting have, and how many bookings does a
   popular room accumulate over its lifetime?**
   *Why it matters:* determines whether O(log n) per-resource conflict checks are actually
   necessary (they are, once n gets into the hundreds/thousands) and whether the free-slot
   merge algorithm's O(n log n) is acceptable (it is, for any realistic attendee-count × history
   size product).
   *Assumption:* attendee counts in the tens, room booking history in the thousands over a
   room's lifetime — well within the complexity budget analyzed in §10 and §14.

6. **Should a cancelled recurring occurrence's slot become immediately available for others to
   book, or does cancellation go through an approval workflow?**
   *Why it matters:* affects whether cancellation needs the same locking discipline as booking.
   *Assumption:* immediate release, no approval workflow — cancellation acquires the exact same
   resource locks as booking (§11) so there's no window where the slot looks busy to a
   conflict-check but isn't actually held by anything.

7. **Do users explicitly declare a home timezone, or is it inferred per-request (e.g. from a
   request header)?**
   *Why it matters:* determines where timezone conversion logic lives.
   *Assumption:* each `User`/`Room` has a declared `ZoneId`; all storage is UTC `Instant`,
   conversion only happens at the presentation edge (§6, `TimeZoneUtil`).

---

## 4. Domain Modelling

| Type | Kind | Responsibility |
|---|---|---|
| `UserId`, `RoomId`, `MeetingId` | Value Object (record) | Type-safe identifiers; prevent passing the wrong kind of id where another is expected. |
| `TimeInterval` | Value Object (record) | The core abstraction: an immutable, half-open `[start, end)` UTC range with `overlaps`/`contains`/`compareTo`. Everything else is built on this. |
| `User` | Entity | A calendar owner; identity = `UserId`; carries a display timezone. |
| `Room` | Entity | A bookable resource; identity = `RoomId`; carries capacity/location/timezone. |
| `RecurrenceRule` | Value Object | An RRULE-inspired, validated recurrence spec (frequency, interval, days-of-week, until/count). Immutable; constructed via static factories (`daily`, `weekly`) rather than a public constructor, so invalid combinations (e.g. WEEKLY with no days) can never exist. |
| `Meeting` | Entity (aggregate root) | A meeting series: organizer, attendees, optional room, base interval, optional recurrence rule, status, exception dates. The only entity with mutable state, and that mutation is only ever performed by `SchedulerService` under lock. |
| `MeetingOccurrence` | Value Object | One concrete, bookable instance of a `Meeting` at a specific `TimeInterval` — what calendars actually index. |
| `BookingRequest` | Value Object (Builder) | Input DTO to `SchedulerService.bookMeeting`. |
| `BookingResult` | Value Object | Output DTO: the created meeting id and every occurrence actually scheduled. |
| `IntervalCalendar` | Interface (domain service / repository-like abstraction) | "The set of booked occurrences for one resource," with conflict-check/book/cancel/query operations. |
| `TreeMapIntervalCalendar` | Domain service impl | The production implementation of `IntervalCalendar` (§10). |
| `AugmentedIntervalTree` | Alternative DSA (not wired into the main flow) | General-purpose "overlaps allowed" interval index, kept to make the DSA trade-off concrete (§10). |
| `RecurrenceExpansionStrategy` (+`Daily`/`WeeklyExpansionStrategy`) | Strategy | Frequency-specific date generation. |
| `RecurrenceExpansionService` | Domain service | Dispatches to the right strategy and converts dates into concrete `TimeInterval`s, timezone-aware. |
| `FreeSlotFinder` | Domain service (stateless, static) | Merge-intervals-based common-free-slot search. |
| `ResourceLockCoordinator` | Infrastructure service | Deadlock-free, timeout-bounded multi-resource locking (§11). |
| `UserRepository`/`RoomRepository`/`MeetingRepository` (+ in-memory impls) | Repository | Persistence abstraction, swappable for a real DB without touching `SchedulerService`'s logic. |
| `BookingEventListener` (+ `ConsoleBookingEventListener`) | Observer | Decouples "booking happened" from "what to do about it." |
| `SchedulerService` | Facade | The single public entry point coordinating everything above. |

**Composition / association / ownership.**
- `Meeting` *has-a* (composes) its own `TimeInterval` and optional `RecurrenceRule` — these are
  value objects with no independent lifecycle; they are created and destroyed with the
  `Meeting`.
- `Meeting` *references* (associates with, does not own) `UserId`s, `RoomId`s — those entities
  have independent lifecycles managed by their own repositories.
- `SchedulerService` *owns* the map of per-resource `IntervalCalendar`s (created lazily,
  one per room/user, for the lifetime of the process) and the `ResourceLockCoordinator`.
- `MeetingOccurrence` is a derived, disposable projection of a `Meeting` — it is created fresh
  every time occurrences are computed/recomputed; it is never stored as a first-class entity on
  its own (this keeps "the meeting" as the single source of truth, with occurrences recomputed
  deterministically from `baseInterval` + `RecurrenceRule` + `exceptionDates`, rather than
  risking the two getting out of sync).

**Why entities vs. value objects matters here (not just DDD trivia):** `User`/`Room`/`Meeting`
are entities (identity-based `equals`/`hashCode` on their id) because they have a lifecycle and
mutable state over time. `TimeInterval`/`MeetingOccurrence`/`BookingRequest`/`BookingResult`/
`RecurrenceRule` are records (value-based equality, fully immutable) because two intervals with
the same start/end *are* the same interval for every purpose in this system — treating them as
entities would be a meaningless abstraction with no behavioural benefit.

---

## 5. Domain Invariants

Business rules that must *always* hold, and exactly where they are enforced:

1. **A `TimeInterval`'s end must be strictly after its start.** Enforced in `TimeInterval`'s
   compact constructor — it is structurally impossible to construct an invalid interval.
2. **A single resource (room or user) never has two overlapping booked occurrences.** Enforced
   by `TreeMapIntervalCalendar.book()`, which always re-checks `findConflict` before inserting,
   *and* by `ResourceLockCoordinator` ensuring no two threads can race past that check for the
   same resource (§11). This is the central invariant of the whole system.
3. **A `RecurrenceRule` with `WEEKLY` frequency always has at least one day-of-week.** Enforced
   in `RecurrenceRule`'s private constructor (only reachable via the validating static
   factories).
4. **A `RecurrenceRule` has exactly one of `until`/`count`, never both, never neither.**
   Enforced the same way — an unbounded recurring series cannot be constructed, guaranteeing
   `RecurrenceExpansionStrategy.expand` always terminates.
5. **Recurrence expansion never produces more than `RecurrenceRule.MAX_OCCURRENCES` (730)
   occurrences**, regardless of what the rule's own bounds say. Enforced in both
   `DailyExpansionStrategy`/`WeeklyExpansionStrategy` via the `maxOccurrences` parameter — a
   structural safety cap independent of (and in addition to) rule-level validation, so a
   malformed or adversarial request cannot exhaust memory/CPU.
6. **A `Meeting` always has at least one attendee** (the organizer counts as an attendee).
   Enforced in `Meeting`'s constructor and redundantly in `BookingRequest.Builder` (which always
   adds the organizer to the attendee set).
7. **Booking a recurring meeting is all-or-nothing across every occurrence and every involved
   resource.** Enforced structurally in `SchedulerService.bookMeeting` by splitting the critical
   section into a pure validation phase (no mutation) followed by a commit phase that is only
   reached if *every* occurrence passed validation on *every* resource — see §9/§11.
8. **A cancelled occurrence's date is recorded as an exception and is never re-generated by a
   later recomputation of the series' occurrences.** Enforced by `RecurrenceExpansionService`
   always filtering generated dates against `Meeting.exceptionDates()`.
9. **A meeting series' occurrence dates, once booked, are deterministically reproducible from
   `baseInterval` + `RecurrenceRule` + `exceptionDates` + `anchorZone` alone.** This is what lets
   cancellation recompute "what is currently booked" without storing a separate occurrence list
   — enforced by never mutating `baseInterval`/`recurrenceRule`/`anchorZone` after construction,
   and by `anchorZone` being captured once, from the organizer's timezone *at booking time*
   (not re-read live), so a later change to the organizer's profile timezone cannot retroactively
   shift where the series' future occurrences are considered to fall.

---

## 6. Architecture and Class Diagram

See:
- `diagrams/component-diagram.md` — component-level view of how `SchedulerService` composes
  locking, recurrence, free-slot search, calendars, repositories, and notification.
- `diagrams/class-diagram.md` — full class diagram matching every class under `src/`.
- `diagrams/sequence-diagrams.md` — sequence diagrams for: booking, the conflict-check
  neighbour-lookup itself, common-free-slot search, and single-occurrence cancellation.

Every class, method name, and relationship in those diagrams corresponds 1:1 to the actual code
under `src/main/java/lld/meetingscheduler` — there is no diagram/code drift; the diagrams were
authored by reading the final implementation, not sketched speculatively beforehand.

---

## 7. Design Patterns

For every pattern used, the alternative considered and why it was rejected (no pattern was
added just to show it off):

### Strategy — `RecurrenceExpansionStrategy` (+ `Daily`/`WeeklyExpansionStrategy`)
- **Why needed:** daily and weekly date-generation logic are genuinely different algorithms
  (step-by-N-days vs. step-by-N-weeks-across-multiple-days-of-week), each independently
  non-trivial and independently testable.
- **Where applied:** `RecurrenceExpansionService` holds a `Map<Frequency, RecurrenceExpansionStrategy>`
  and dispatches based on the rule's frequency.
- **Alternative considered:** a single method with a `switch` on `Frequency`.
- **Why rejected:** adding `MONTHLY` later would require editing that one giant method (and
  risk breaking `DAILY`/`WEEKLY` logic while doing so) rather than adding one new class and one
  map entry. With exactly two cases today, this is a light-touch application of the pattern —
  not overengineered, because the interface is one method and each implementation is
  ~20 lines.

### Builder — `BookingRequest.Builder`
- **Why needed:** `BookingRequest` has 3 required fields and 4 optional ones (room,
  recurrence, idempotency key, plus a varying-size attendee set); a telescoping constructor
  would be unreadable and error-prone at the call site.
- **Where applied:** `BookingRequest.builder()...build()`.
- **Alternative considered:** a single constructor taking `Optional<...>` parameters directly.
- **Why rejected:** still requires callers to pass `Optional.empty()` explicitly for every
  unused optional field in a fixed position, which is exactly the telescoping-constructor
  readability problem restated with `Optional` boilerplate.

### Facade — `SchedulerService`
- **Why needed:** a client needs one coherent API (book / cancel / find-free-slots) without
  needing to know about `ResourceLockCoordinator`, per-resource calendars, or
  `RecurrenceExpansionService` individually.
- **Where applied:** `SchedulerService` is the sole public entry point used by `SchedulerDemo`
  and the test suite.
- **Alternative considered:** expose `IntervalCalendar`, `RecurrenceExpansionService`, etc.
  directly to callers.
- **Why rejected:** that would leak the locking protocol to every call site — any caller who
  forgot to acquire locks in the correct sorted order could reintroduce the double-booking bug
  the whole design exists to prevent. Centralizing orchestration in one class makes "always
  lock correctly" a single-point-of-truth property instead of a convention every caller must
  remember.

### Observer — `BookingEventListener`
- **Why needed:** "a meeting was booked/cancelled" naturally has zero-to-many independent
  reactions (console log in the demo; email/push/metrics in production) that should not be
  hardcoded into the scheduling logic.
- **Where applied:** `SchedulerService.addListener(...)`, invoked after every successful commit.
- **Alternative considered:** have `SchedulerService` directly call a `NotificationService`.
- **Why rejected:** that still couples the scheduler to one specific notification mechanism and
  makes adding a second channel (e.g. metrics) require editing `SchedulerService` again,
  violating Open/Closed.

### Repository — `UserRepository`/`RoomRepository`/`MeetingRepository`
- **Why needed:** `SchedulerService` should not know or care whether users/rooms/meetings live
  in memory or a SQL database.
- **Where applied:** interfaces with `InMemory*` implementations wired in via constructor
  injection.
- **Alternative considered:** `SchedulerService` talks directly to `ConcurrentHashMap`s.
- **Why rejected:** would hardcode the in-memory decision into the facade itself, making the
  (very real, see §12/§17) future move to a database a rewrite instead of a new
  implementation class.

### Patterns deliberately *not* used
- **Singleton** — `SchedulerService` and the repositories are constructed once by whoever wires
  up the application (see `SchedulerDemo.main`), not forced into a global singleton. A
  singleton would make unit testing harder (shared global state across tests) for zero benefit.
- **Command** (for cancel/undo) — cancellation here is a direct, synchronous operation with no
  need for queuing, replay, or undo history; introducing `Command` objects would add a layer of
  indirection with no corresponding requirement.
- **Visitor** over `Meeting`/`Room`/`User` — there is no family of unrelated operations that
  need to be added to these types without modifying them; this would be speculative
  generality.

---

## 8. API and Method Design

### `SchedulerService` (the public surface)

```java
BookingResult bookMeeting(BookingRequest request);
void cancelMeeting(MeetingId meetingId, CancellationScope scope, LocalDate occurrenceDate);
List<TimeInterval> findCommonFreeSlots(
        Set<UserId> attendeeIds, Optional<RoomId> roomId, TimeInterval window, Duration minDuration);
void addListener(BookingEventListener listener);
```

- `bookMeeting` throws `ResourceNotFoundException` (unknown organizer/room),
  `InvalidMeetingRequestException` (unknown attendee, malformed request, zero occurrences
  after expansion), `MeetingConflictException` (a resource is busy), and
  `SchedulingTimeoutException` (lock contention timeout). All are unchecked — callers opt in to
  handling specific failure modes rather than being forced to catch-or-declare everything,
  consistent with how most modern Java service-layer APIs are designed.
- `cancelMeeting` throws `ResourceNotFoundException` (unknown meeting) and
  `InvalidMeetingRequestException` (no occurrence matches the given scope/date).
- Validation responsibility split: **request-shape validation** (blank title, missing required
  fields) happens in `BookingRequest`'s constructor/Builder, fully independent of any resource
  state. **Domain validation** (does the organizer/room/attendee actually exist) happens in
  `SchedulerService` before any lock is taken. **Conflict validation** (is the resource free)
  happens inside the locked critical section, because it depends on shared mutable state that
  only the lock makes safe to read.

### `BookingRequest.Builder` (request construction)
```java
BookingRequest.builder()
    .title("Design Review")
    .organizer(organizerId)
    .addAttendee(attendeeId)       // or .attendees(Set<UserId>)
    .room(roomId)                  // optional
    .interval(timeInterval)
    .recurrence(recurrenceRule)    // optional
    .idempotencyKey("client-key")  // optional
    .build();
```

### `IntervalCalendar` (the per-resource conflict-detection seam)
```java
void book(MeetingOccurrence occurrence);                       // throws MeetingConflictException
Optional<MeetingOccurrence> findConflict(TimeInterval candidate);
boolean cancel(Instant occurrenceStart);
List<MeetingOccurrence> occurrencesOverlapping(TimeInterval window);
int size();
```

### `RecurrenceRule` (validated construction via static factories, no public constructor)
```java
RecurrenceRule.daily(int interval, LocalDate until);
RecurrenceRule.daily(int interval, int count);
RecurrenceRule.weekly(int interval, Set<DayOfWeek> daysOfWeek, LocalDate until);
RecurrenceRule.weekly(int interval, Set<DayOfWeek> daysOfWeek, int count);
```

---

## 9. Core Implementation

Full, compiling Java source is in `src/main/java/lld/meetingscheduler`. The components most
central to the design (reproduced here for reference; the files under `src/` are the
authoritative, compiled, tested source):

### `TimeInterval` — the foundational value object
See `src/main/java/lld/meetingscheduler/model/TimeInterval.java`. Half-open `[start, end)`
semantics via a single `overlaps` expression:
```java
public boolean overlaps(TimeInterval other) {
    return this.start.isBefore(other.end) && other.start.isBefore(this.end);
}
```

### `TreeMapIntervalCalendar` — the DSA core
See `src/main/java/lld/meetingscheduler/interval/TreeMapIntervalCalendar.java`. The conflict
check never looks at more than two existing entries:
```java
public synchronized Optional<MeetingOccurrence> findConflict(TimeInterval candidate) {
    var predecessor = bookingsByStart.floorEntry(candidate.start());
    if (predecessor != null && predecessor.getValue().interval().overlaps(candidate)) {
        return Optional.of(predecessor.getValue());
    }
    var successor = bookingsByStart.ceilingEntry(candidate.start());
    if (successor != null && successor.getValue().interval().overlaps(candidate)) {
        return Optional.of(successor.getValue());
    }
    return Optional.empty();
}
```
Full proof of correctness for why only these two neighbours need checking is in the class's
javadoc and §10 below.

### `ResourceLockCoordinator` — deadlock-free multi-resource locking
See `src/main/java/lld/meetingscheduler/service/ResourceLockCoordinator.java`. Sorted lock
acquisition with bounded wait:
```java
public <T> T withLocks(Collection<String> resourceKeys, Duration timeout, Callable<T> criticalSection) {
    List<String> sortedKeys = new ArrayList<>(new TreeSet<>(resourceKeys));
    List<ReentrantLock> acquired = new ArrayList<>(sortedKeys.size());
    try {
        for (String key : sortedKeys) {
            ReentrantLock lock = lockFor(key);
            if (!lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new SchedulingTimeoutException("Timed out acquiring lock for resource '" + key + "'");
            }
            acquired.add(lock);
        }
        return criticalSection.call();
    } finally {
        for (int i = acquired.size() - 1; i >= 0; i--) acquired.get(i).unlock();
    }
}
```

### `SchedulerService.bookMeeting` — the two-phase validate/commit orchestration
See `src/main/java/lld/meetingscheduler/service/SchedulerService.java`. The method body (full
listing in the source file) is structured as: validate request shape → validate
organizer/attendees/room exist → expand recurrence → acquire all resource locks → **Phase 1**
(pure validation loop over every occurrence × every resource, no mutation) → **Phase 2** (commit:
persist `Meeting`, book every occurrence on every resource, cache idempotency key, notify
listeners) → release locks.

### Runnable demo
`src/main/java/lld/meetingscheduler/demo/SchedulerDemo.java` exercises all of: a one-off
booking, a rejected conflicting booking, a recurring weekly booking, common-free-slot search,
single-occurrence cancellation, and a 10-thread concurrent race for the same slot (verified to
produce exactly one winner). Run it with:
```bash
javac -d out $(find src -name '*.java') && java -cp out lld.meetingscheduler.demo.SchedulerDemo
```

---

## 10. Data Structures and Algorithms

This is the heart of the problem statement's guidance ("efficient data structure... discuss
O(log n) interval insertion/overlap-check vs naive O(n) scan") — treated rigorously below.

### The core problem
For one resource (one room, one attendee), given a new candidate `TimeInterval`, determine
whether it overlaps any already-booked interval on that resource.

### Option A (rejected): unsorted list, linear scan
Store bookings in a `List<TimeInterval>`; on every new candidate, iterate the whole list calling
`overlaps` on each entry.
- **Insert:** O(1) (append) — but only *after* a conflict check, which is O(n).
- **Conflict check:** O(n) — must examine every existing booking, because there is no ordering
  to exploit.
- **Cancel:** O(n) (linear search + remove).
- **Free-slot query over a window:** O(n) (must still scan everything, then filter).
- **Why rejected:** on a popular room with, say, 2,000 historical bookings, every new booking
  attempt does 2,000 interval comparisons. This gets worse, not better, over the room's
  lifetime — exactly the "naive O(n) scan" the problem statement calls out.

### Option B (chosen): `TreeMap<Instant, MeetingOccurrence>` per resource
Implemented in `TreeMapIntervalCalendar`. **The key exploitable fact:** for a single resource,
all existing bookings are *already pairwise non-overlapping* (that's the invariant we're
enforcing) — so they form a sorted sequence of disjoint ranges once ordered by start time. For a
new candidate interval, only its **immediate predecessor** (`floorEntry(candidate.start)`) and
**immediate successor** (`ceilingEntry(candidate.start)`) in that sorted sequence can possibly
overlap it:

- If the predecessor's end is `<= candidate.start`, it ends before (or exactly at) the candidate
  starts — no overlap (half-open semantics) — and because every entry *before* the predecessor
  ends even earlier still (sorted + disjoint), none of them overlap either, by transitivity.
- Symmetrically for the successor and every entry after it.

This turns an apparently-necessary "check against everything" into "check against exactly two
things," using the JDK's balanced red-black tree (`TreeMap`) to get the predecessor/successor in
O(log n).

| Operation | Naive list | `TreeMapIntervalCalendar` |
|---|---|---|
| `findConflict` | O(n) | **O(log n)** |
| `book` (check + insert) | O(n) | **O(log n)** |
| `cancel` | O(n) | **O(log n)** |
| `occurrencesOverlapping(window)` | O(n) | **O(log n + k)**, k = results returned |

**Space:** O(n) for both — no trade-off there; the win is purely algorithmic.

### Option C (discussed, implemented for comparison, not used on the hot path): augmented interval tree
`AugmentedIntervalTree` (in `src/main/java/lld/meetingscheduler/interval`) is a classic
max-end-augmented BST supporting **true multi-overlap queries** — "give me every interval that
overlaps this query," not just "does any single thing conflict." This is strictly more general
than what `TreeMapIntervalCalendar` needs, because it does **not** assume existing intervals are
pairwise disjoint.

- **When Option C would actually be the right choice:** if the clarifying-question answer in
  §3.1 were "yes, a resource can have legitimately overlapping bookings up to some capacity" —
  e.g. an open-seating room, or "show me every meeting that touches this afternoon" reporting
  queries where you need *all* overlaps, not just a yes/no. In that world, the
  disjoint-neighbours trick doesn't apply (there is no "only two neighbours can overlap"
  guarantee once overlaps are allowed by design), and a true interval tree with max-end
  augmentation and subtree pruning (see the class's `findAllOverlapping` javadoc) is the correct
  tool: O(log n + k) to enumerate every one of k overlaps.
- **Why it's not used here anyway:** for this problem's actual invariant (single-occupancy
  resources), `TreeMapIntervalCalendar` gets the *same* asymptotic complexity for the operation
  that matters (conflict detection) while reusing the JDK's self-balancing tree instead of a
  hand-rolled, unbalanced one (this implementation's `insert` is a plain BST insert — O(log n)
  average, O(n) worst case on adversarial insertion order, unlike `TreeMap`'s guaranteed
  O(log n)). Introducing a hand-rolled interval tree where a simpler, better-balanced structure
  already gets the job done would be over-engineering.

### Recurrence expansion
`DailyExpansionStrategy`: O(k) where k = occurrences generated (bounded by
`RecurrenceRule.MAX_OCCURRENCES` = 730). `WeeklyExpansionStrategy`: O(k) as well — one iteration
per (week, day-of-week) pair up to the bound. Both are linear in the output size, which is the
best possible (you cannot produce k occurrences in better than O(k)).

### Common free-slot search
`FreeSlotFinder` implements the standard merge-intervals algorithm: flatten every busy interval
from every involved calendar (O(n) to collect, where n = total busy intervals in the window
across all resources), sort (O(n log n)), merge overlapping/touching intervals into maximal busy
blocks (O(n)), then walk the gaps between merged blocks looking for ones `>= minDuration` (O(n)).
**Total: O(n log n)**, dominated by the sort. This is asymptotically optimal for a
comparison-based approach — any correct algorithm must examine every busy interval at least once
(Ω(n)), and sorting n arbitrary items is Ω(n log n) in the comparison model. A possible further
optimization (noted, not implemented, because it doesn't change the asymptotic class for
realistic attendee counts): each individual attendee's busy list is already sorted coming out of
`occurrencesOverlapping`, so a k-way merge of k pre-sorted lists is O(n log k) instead of
O(n log n) — worth it only when k (the attendee count) is large relative to n.

---

## 11. Concurrency and Thread Safety

**Every claim below is backed by a passing test** in
`tests/lld/meetingscheduler/service/SchedulerConcurrencyTest.java`, which launches real threads
with a `CountDownLatch`-synchronized start to maximize actual contention, not just asserted in
prose.

### Where the shared mutable state is
1. Each resource's `TreeMapIntervalCalendar` (a mutable `TreeMap` of bookings).
2. `SchedulerService`'s `roomCalendars`/`userCalendars` maps (which calendar object exists for
   which resource id) and `idempotencyCache`.
3. `Meeting.status` and `Meeting.exceptionDates` (mutated by cancellation).

### The race condition this system must prevent
Two threads, T1 booking (Room-1, Alice, Bob) and T2 booking (Room-1, Carol), for overlapping
times. Both see "Room-1 is free" if they read-then-write without synchronization (TOCTOU:
time-of-check to time-of-use) — both proceed to book, and Room-1 ends up double-booked.

### The chosen protocol: per-resource `ReentrantLock`s, acquired in a fixed global sorted order, around a validate-then-commit critical section
`ResourceLockCoordinator.withLocks` (full javadoc in the source) does three things:
1. **Deduplicates and sorts** every resource key a booking touches (one room key + N attendee
   keys) into one fixed, global, lexicographic order.
2. **Acquires every lock in that order**, using `tryLock(timeout, unit)` rather than unbounded
   `lock()`.
3. Runs the validate-then-commit critical section only once every lock is held, and releases
   all locks (in reverse order) in a `finally` block regardless of outcome.

**Why sorted-order acquisition prevents deadlock:** deadlock requires circular wait (one of the
four Coffman conditions). If every thread that might contend for overlapping resource sets
always acquires those resources in the same relative order, no cycle can form in the
wait-for graph: thread A can only be waiting for a lock with a "larger" key than every lock it
already holds, so it is structurally impossible for A to hold lock X while waiting for lock Y,
*and* for some other thread to hold Y while waiting for X.

**Why `tryLock` with a timeout, not `lock()`:** sorted ordering rules out deadlock, but not
bounded contention — many threads legitimately racing for the exact same popular room could, in
principle, each wait a long time. A bounded wait that fails fast with
`SchedulingTimeoutException` (a retryable, 409/429-equivalent outcome) is safer for a live
service than a thread blocked indefinitely.

**Why the critical section is split into a pure validation phase and a commit phase:** this is
what makes a *recurring* booking all-or-nothing. If occurrence 3 of 5 conflicted and the code
booked occurrences 1-2 before discovering that, it would need complex rollback logic (and would
be observably inconsistent to any concurrent reader between the partial commit and the
rollback). Validating every occurrence against every resource first, with zero mutation, means
the commit phase — once reached — is guaranteed to succeed on every single `book()` call (no
exceptions possible there), so there is no rollback path to get wrong.

**Why pessimistic locking over optimistic concurrency (CAS/versioning):** an optimistic approach
needs every calendar to expose a version/CAS-capable API and a retry loop at every call site, and
its retry cost scales with the probability of conflict, which is exactly the thing that spikes
under the heavy-contention scenario (many threads vs. one popular room) this design is meant to
handle well. Pessimistic locking is simpler to reason about, and its downside (threads block
during the critical section) is cheap here because the critical section itself is tiny — a
handful of O(log n) `TreeMap` lookups — so *lock hold time*, not the locking strategy, is what
would actually matter for throughput, and hold time is already minimal.

### Lock granularity
One `ReentrantLock` **per resource** (per room, per user) — not one global lock for the whole
scheduler. This means two bookings for *disjoint* resource sets (e.g. Room-1+Alice vs.
Room-2+Bob) proceed fully in parallel with zero contention, verified by
`concurrentBookingsOnDisjointSlotsAllSucceed` (20 threads, 20 non-overlapping slots, all
succeed). A global lock would serialize every booking in the whole system regardless of
resource overlap — correct, but needlessly limits throughput.

### Visibility
- `TreeMapIntervalCalendar`'s methods are `synchronized` on the instance — this gives both
  mutual exclusion *and* the Java Memory Model's happens-before guarantee between a write and a
  subsequent read by another thread, so a booking made by one thread is guaranteed visible to
  the next thread that successfully acquires the same lock and reads the calendar. (This is
  defense-in-depth: `ResourceLockCoordinator`'s locks are what *actually* serialize access across
  the whole multi-resource operation, since two calendar mutations for the same booking must be
  atomic *together*, which a per-calendar `synchronized` alone cannot provide.)
- `Meeting.status` is `volatile` so a cancellation becomes visible to any thread reading status
  without needing its own lock (read-mostly field, write-rarely, no compound check-then-act on
  it alone).
- `Meeting.exceptionDates` is a `CopyOnWriteArraySet` — safe to iterate concurrently with a
  write (e.g. rendering a meeting's cancelled dates while another thread is mid-cancellation of
  a different occurrence) without external synchronization, appropriate given the
  read-heavy/write-light access pattern (writes only happen on cancellation, an infrequent
  operation relative to reads).
- `SchedulerService`'s `roomCalendars`/`userCalendars`/`idempotencyCache` are
  `ConcurrentHashMap`s with `computeIfAbsent` for lazy calendar creation — `computeIfAbsent` is
  atomic per key, so two threads racing to create "the calendar for Room-1 for the first time"
  are guaranteed to end up sharing the exact same calendar instance, not two different ones
  (which would silently defeat all conflict detection).

### Idempotency
`BookingRequest.idempotencyKey()` lets a client safely retry a booking call after a
network/timeout failure without risking a duplicate booking: `SchedulerService` checks the
`idempotencyCache` *before* taking any locks and returns the cached `BookingResult` if the key
was already used. **Caveat, stated explicitly:** this in-memory cache does not survive a process
restart and has no TTL/eviction — production would need a durable, TTL'd idempotency store
(§17).

### Deadlock — concretely, why it cannot happen here
Suppose T1 books (Room-1, Alice) and T2 books (Alice, Room-1) concurrently (same resources,
request built with attendees in the opposite order). Both sort their resource keys into the
identical list `["ROOM:1", "USER:alice"]` before acquiring anything. Whichever thread acquires
`"ROOM:1"` first will also be the only one able to proceed to request `"USER:alice"` next; the
other blocks on `"ROOM:1"` first and never holds `"USER:alice"` while waiting for `"ROOM:1"`.
No cycle is possible.

---

## 12. Persistence and Database Design

**This solution is deliberately in-memory** (`InMemoryUserRepository`,
`InMemoryRoomRepository`, `InMemoryMeetingRepository`, backed by `ConcurrentHashMap`), and that
is a conscious, justified choice for an LLD exercise rather than an oversight:

- The problem is fundamentally about **domain modelling, algorithmic conflict detection, and
  concurrency correctness** — not about SQL schema design or query optimization. A real
  interview that asks "design a meeting scheduler" is testing the former; introducing a
  database would shift a large fraction of the available time into schema/ORM plumbing that
  does not exercise the skills being evaluated.
- The `Repository` interfaces (§7) are the seam that makes swapping to a real database a
  **new implementation class**, not a redesign — `SchedulerService` depends only on
  `UserRepository`/`RoomRepository`/`MeetingRepository` interfaces.

**If this were being productionized**, the natural schema would be:

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE,
    timezone TEXT NOT NULL
);

CREATE TABLE rooms (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    capacity INT NOT NULL CHECK (capacity > 0),
    location TEXT NOT NULL,
    timezone TEXT NOT NULL
);

CREATE TABLE meetings (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    organizer_id UUID NOT NULL REFERENCES users(id),
    room_id UUID REFERENCES rooms(id),
    base_start TIMESTAMPTZ NOT NULL,
    base_end TIMESTAMPTZ NOT NULL,
    anchor_zone TEXT NOT NULL,
    recurrence_frequency TEXT,          -- NULL for one-off meetings
    recurrence_interval INT,
    recurrence_days_of_week TEXT,       -- e.g. 'MON,WED'
    recurrence_until DATE,
    recurrence_count INT,
    status TEXT NOT NULL DEFAULT 'SCHEDULED'
);

CREATE TABLE meeting_attendees (
    meeting_id UUID NOT NULL REFERENCES meetings(id),
    user_id UUID NOT NULL REFERENCES users(id),
    PRIMARY KEY (meeting_id, user_id)
);

CREATE TABLE meeting_exception_dates (
    meeting_id UUID NOT NULL REFERENCES meetings(id),
    exception_date DATE NOT NULL,
    PRIMARY KEY (meeting_id, exception_date)
);

-- Materialized occurrences: generated at booking time and persisted, so the double-booking
-- check can be a single indexed range query instead of re-expanding every series on every read.
CREATE TABLE meeting_occurrences (
    meeting_id UUID NOT NULL REFERENCES meetings(id),
    occurrence_start TIMESTAMPTZ NOT NULL,
    occurrence_end TIMESTAMPTZ NOT NULL,
    room_id UUID REFERENCES rooms(id),
    PRIMARY KEY (meeting_id, occurrence_start)
);

-- The index that makes conflict-checking efficient at the database layer, mirroring the
-- TreeMap's neighbour trick: a range query, not a full scan.
CREATE INDEX idx_occurrences_room_range ON meeting_occurrences (room_id, occurrence_start, occurrence_end);
CREATE INDEX idx_occurrences_attendee_range ON meeting_attendee_occurrences (user_id, occurrence_start, occurrence_end);
```

- **Transactions/isolation:** booking a recurring meeting (insert `meetings` row + N rows in
  `meeting_occurrences` + conflict check) must be one transaction at `SERIALIZABLE` (or
  `REPEATABLE READ` with an exclusion constraint — PostgreSQL's `EXCLUDE USING gist` on a
  `tsrange` column is the idiomatic way to enforce "no two rows for the same room with
  overlapping ranges" *at the database level*, which would actually subsume the application-level
  `TreeMap` check and be the production equivalent of §10/§11's in-memory guarantee).
- **Why an exclusion constraint instead of application-level locking in production:** it moves
  the double-booking invariant into the data layer itself, where it holds even if a second
  service instance, a direct SQL script, or a bug bypasses `SchedulerService` entirely — a
  stronger guarantee than any amount of in-process locking can provide.

---

## 13. Failure Scenarios

| Scenario | Handling |
|---|---|
| **Partial failure mid-booking** (process crash after persisting `Meeting` but before booking every occurrence) | Cannot happen in this single-process, in-memory design (no I/O boundary between persist and book — a crash loses *everything* uncommitted equally). In a DB-backed production version, this is exactly what the single `SERIALIZABLE` transaction in §12 prevents: either the whole insert (meeting + occurrences) commits, or none of it does. |
| **Duplicate booking from a retried client request** | Handled by the idempotency key (§11) — a retry with the same key returns the original `BookingResult` instead of creating a second meeting. |
| **Invalid state: recurrence rule produces zero occurrences** (e.g. `until` before the series start) | `bookMeeting` explicitly checks `occurrences.isEmpty()` after expansion and throws `InvalidMeetingRequestException` rather than silently creating a meeting with nothing booked. |
| **Resource exhaustion: pathological recurrence rule** (e.g. daily, no `until`, far-future `count`) | `RecurrenceRule`'s factories cap `count <= MAX_OCCURRENCES` (730) and the expansion strategies independently also respect a `maxOccurrences` parameter — defense in depth; a malformed rule cannot cause unbounded memory/CPU use regardless of which validation layer is checked. |
| **Lock-acquisition timeout under heavy contention** | `SchedulingTimeoutException` is thrown (not an indefinite hang), a distinct, explicitly retryable failure mode a caller can catch and back off on. |
| **Cancelling an occurrence that was never booked, or a date not covered by the series** | `cancelMeeting` computes `toCancel` as the actual intersection of the series' currently-booked occurrences and the requested scope/date; if that intersection is empty, it throws `InvalidMeetingRequestException` rather than silently doing nothing (which would hide a client bug — e.g. a typo'd date — behind a false "success"). |
| **Referencing an unknown user/room/meeting** | `ResourceNotFoundException` (organizer/room/meeting lookups) or `InvalidMeetingRequestException` (attendee lookups) thrown before any lock is taken — fails fast, cheaply, with no wasted lock contention. |
| **Corruption: two calendars disagreeing about whether an occurrence is booked** (e.g. room says booked, one attendee says free) | Cannot arise given the all-or-nothing commit protocol (§9/§11) — every occurrence is booked on *every* involved resource inside the same held locks, or on none of them. There is no code path that books a subset. |
| **Cleanup after a rejected booking** | None needed — the validation phase performs zero mutation (§11), so a rejected booking leaves every calendar byte-for-byte as it was before the call; there is nothing to roll back. |
| **Interrupted thread while waiting for a lock** | `ResourceLockCoordinator` catches `InterruptedException` from `tryLock`, restores the interrupt flag (`Thread.currentThread().interrupt()`), and converts it to a `SchedulingTimeoutException` — the interrupt status is never silently swallowed, and any already-acquired locks are still released via the `finally` block. |

---

## 14. Performance and Scalability

### Single-process bottlenecks and fixes
- **Bottleneck:** a single extremely popular shared resource (e.g. the one all-hands room)
  serializes every booking attempt against it through one `ReentrantLock`.
  **Fix (already in place):** per-resource locking (not a global lock) means this contention is
  isolated to that one resource; every other room/user books in full parallel. Further
  mitigation if this specific resource remains hot: shard its calendar by time range (e.g. one
  lock per month) so bookings for different months don't contend — not implemented here because
  it's unnecessary at the stated scale (§3.5), but a natural next step.
- **Bottleneck:** `findCommonFreeSlots` across a very large attendee list — O(n log n) where n
  grows with both attendee count and each attendee's booking density in the window.
  **Fix:** the k-way-merge optimization noted in §10 (O(n log k) instead of O(n log n)) becomes
  worth implementing once attendee counts are large (hundreds+) relative to typical per-attendee
  busy-interval counts in a search window.

### Multi-threaded (single process, many cores) optimizations
- Already exploited: per-resource lock granularity lets bookings for disjoint resource sets run
  fully in parallel (verified by `concurrentBookingsOnDisjointSlotsAllSucceed`).
- `ConcurrentHashMap` for the calendar/idempotency registries avoids a global lock just to look
  up or lazily create a per-resource calendar.

### Distributed-extension optimizations (explicitly *not* implemented — this is an LLD problem, not a distributed-systems one)
- **Sharding calendars across nodes** by room id / user id hash, with a consistent-hashing
  router in front — the per-resource lock already in this design maps naturally onto "owning
  node for that shard holds the lock," so this design does not need to be redesigned to shard,
  only extended.
- **Database-level enforcement** (§12's exclusion constraint) as the cross-node source of truth,
  with in-memory calendars demoted to a read-through cache rather than the system of record.
- Explicitly **not** doing: distributed consensus (Raft/Paxos) for booking decisions — massive
  overkill for this problem; a single strongly-consistent database with row-level exclusion
  constraints already gives the correctness property needed, at far lower operational
  complexity. This is the "do not over-distribute an LLD problem" guidance taken seriously: the
  honest answer to "how would this scale to 10M users" (see `STAFF_LEVEL_DEEP_DIVE.md`) is
  "shard the database and keep the locking discipline the same," not "introduce a consensus
  protocol."

---

## 15. Testing Strategy

All tests are under `tests/lld/meetingscheduler`, run via JUnit 5 (console-standalone launcher
verified; see the final verification note at the end of this document). **49/49 tests pass.**

| Category | Representative test(s) |
|---|---|
| **Unit — value object correctness** | `TimeIntervalTest` — half-open overlap semantics, boundary-touching non-overlap, ordering, construction validation. |
| **Unit — DSA correctness** | `TreeMapIntervalCalendarTest` — conflict detected via predecessor only, via successor only, no false positive across a gap, back-to-back bookings succeed, cancel-then-rebook. `AugmentedIntervalTreeTest` — multi-overlap support, subtree pruning correctness. |
| **Unit — recurrence expansion** | `RecurrenceExpansionServiceTest` — daily by count, daily with a step interval, weekly on multiple days (order + count), weekly starting mid-week (elapsed-day skip), weekly with a step interval, exception-date exclusion, `until`-bounded termination, time-of-day/duration preservation across occurrences. |
| **Unit — free-slot algorithm** | `FreeSlotFinderTest` — empty-busy-list returns full window, overlapping busy intervals merged before gap computation, sub-minimum gaps excluded, busy-touching-boundary leaves no spurious gap, multi-attendee combination. |
| **Integration — the facade** | `SchedulerServiceTest` — one-off booking, room conflict rejection, attendee conflict rejection across different rooms, back-to-back success, **recurring booking all-or-nothing** (proves the partial-conflict case correctly books *nothing*), idempotency-key replay, unknown organizer/attendee rejection, all three cancellation scopes, common-free-slot search end-to-end. |
| **Boundary** | Touching-interval tests (both at the `TimeInterval` level and the calendar level); `until`-exactly-on-boundary recurrence termination. |
| **Negative** | Unknown organizer → `ResourceNotFoundException`; unknown attendee → `InvalidMeetingRequestException`; cancelling a non-matching scope/date → `InvalidMeetingRequestException` (exercised implicitly via the scoped-cancellation tests' negative-space assertions). |
| **Concurrency / failure-injection** | `SchedulerConcurrencyTest` — 16 real threads racing (via `CountDownLatch`-synchronized start) for the *exact same* room+slot, repeated 5 times, asserting **exactly one** succeeds and the other 15 observe `MeetingConflictException` every single run; a second test with 20 threads on 20 disjoint slots asserting all 20 succeed (proving the locking discipline doesn't *over*-serialize independent work). |

**Why these tests, specifically, were chosen:** every test targets a property that could
plausibly be broken by a *plausible* bug (e.g. "what if someone changed `overlaps` to use
`<=` instead of `<`, making touching intervals falsely conflict" — caught by
`touchingBoundaryIsNotOverlap` and `backToBackBookingsOnSameRoomSucceed`), not a restatement of
the implementation or a trivial accessor check.

---

## 16. Trade-offs and Alternatives

| Decision | Chosen | Alternative | Why chosen / when alternative wins |
|---|---|---|---|
| Conflict-check data structure | `TreeMap` per resource (neighbour-check) | Hand-rolled balanced interval tree | Chosen gets identical complexity while reusing the JDK's guaranteed-balanced tree; alternative wins only if overlapping bookings on one resource must be *allowed* (see §10 Option C). |
| Recurring booking atomicity | All-or-nothing across occurrences | Best-effort (book whichever occurrences don't conflict, skip the rest) | Chosen avoids silently creating a sparse series the organizer didn't ask for and avoids rollback-on-partial-failure complexity; alternative wins in a product where "get me as many as possible" is the actual desired UX (a legitimate, different product decision — not implemented here per §3.4's stated assumption). |
| Locking strategy | Pessimistic, sorted-order, timeout-bounded | Optimistic (versioned read + CAS + retry) | Chosen is simpler to reason about and the critical section is cheap; alternative wins under very high read-to-write ratios with rare actual conflicts, where avoiding any lock entirely on the common path matters more than simplicity (not the case here — booking *is* the write path). |
| Persistence | None (in-memory) | SQL database with exclusion constraints | Chosen keeps focus on algorithmic/concurrency correctness, the actual subject of the exercise; alternative is the right call the moment durability across restarts or multi-node correctness is required (§12, §17). |
| Recurrence grammar | Daily/weekly, RRULE-*inspired* | Full RFC 5545 RRULE parser | Chosen matches the stated scope and is extensible (§7 Strategy pattern) without the multi-week investment a full RRULE engine requires; alternative wins if the product genuinely needs monthly/yearly/BYSETPOS patterns. |
| Occurrence storage | Recomputed deterministically from `baseInterval`+`RecurrenceRule`+`exceptionDates` | Persisted materialized occurrence list per series | Chosen keeps a single source of truth with zero risk of the two drifting apart; alternative (§12's `meeting_occurrences` table) is the right call once query performance on "all occurrences in this date range across all series" matters enough to justify an index-backed materialized table — exactly what the production schema does. |
| Notification | Observer interface, console impl only | Fully built email/push integration | Chosen demonstrates the extensibility seam without scope creep into an unrelated subsystem (SMTP clients, push tokens); alternative is what a real product obviously needs, deferred to §17. |

---

## 17. Production Evolution

Kept separate from the core interview solution on purpose — these are the things a staff
engineer would flag as "next steps if we ship this," not things this LLD exercise needs to
implement to be complete:

- **Monitoring/metrics:** booking success/conflict/timeout rates per resource (to spot a
  perpetually-contended room before users complain), lock-wait-time histograms (to catch lock
  granularity becoming too coarse as load grows), recurrence-expansion size distribution (to
  catch rules trending toward the `MAX_OCCURRENCES` cap).
- **Logging:** structured, correlation-id-tagged logs around every booking/cancellation
  decision (who, what resource, what occurrence, conflict-or-not) — necessary for support
  tickets ("why can't I book this room") that this in-memory demo currently only answers via an
  exception message.
- **Distributed coordination:** moving from per-process `ReentrantLock`s to the database-level
  exclusion constraint (§12) as soon as more than one scheduler process exists — in-process
  locks only coordinate threads within one JVM.
- **Persistence:** the SQL schema in §12, with the `meeting_occurrences` table materialized at
  write time so reads (conflict checks, free-slot search) never need to re-expand a recurrence
  rule.
- **Fault tolerance:** idempotency keys backed by a durable, TTL'd store (e.g. Redis with a
  24-hour expiry) instead of the in-memory `ConcurrentHashMap` used here, so a retried request
  is still deduplicated correctly across a process restart.
- **Ops concerns:** a real notification channel (email/push) behind `BookingEventListener`,
  rate-limiting on booking attempts per user (abuse prevention), and a documented, versioned
  public API contract (OpenAPI/protobuf) replacing the in-process Java method signatures used
  here.
- **API evolution / backward compatibility:** adding `MONTHLY`/`YEARLY` recurrence would add new
  `Frequency` enum values and new `RecurrenceExpansionStrategy` implementations — existing
  stored `DAILY`/`WEEKLY` rules and their expansion logic are untouched, and a database schema
  migration would only need to relax the `recurrence_frequency` column's check constraint, not
  restructure the table.

---

## Final Verification

```bash
# Compile main sources
javac -d .build/classes $(find src -name '*.java')

# Run the runnable demo
java -cp .build/classes lld.meetingscheduler.demo.SchedulerDemo

# Compile + run the full JUnit 5 test suite (console-standalone launcher)
javac -d .build/test-classes -cp junit-platform-console-standalone.jar $(find src tests -name '*.java')
java -jar junit-platform-console-standalone.jar --class-path .build/test-classes --scan-class-path
```

**Result:** main sources compile cleanly with zero warnings/errors; the demo runs end-to-end
exactly as narrated in §9; **49 of 49 JUnit 5 tests pass**, including 5 repetitions of the
16-thread double-booking race test (each repetition independently confirms exactly 1 winner and
15 conflicts).
