# Staff-Level Deep Dive — Meeting Scheduler

Twenty-four problem-specific, challenging questions with reasoned answers — not generic
"tell me about SOLID" questions (those are in `FOLLOW_UP_QUESTIONS.md`). These are the
questions that probe whether the *specific* architectural decisions in this design hold up
under pressure.

---

### 1. Your conflict-check relies on "a single resource never has overlapping bookings." What breaks if that invariant is ever violated by a bug, and how would you detect it?

If the invariant is violated — say, a bug in `ResourceLockCoordinator` lets two threads race
past `findConflict` simultaneously and both call `book()` — the `TreeMap` itself does not
detect the violation; it will happily store two overlapping entries keyed by different start
instants. The *symptom* is silent: future `findConflict` calls checking only the immediate
floor/ceiling neighbours can now miss a conflict, because the neighbour-check proof depends on
the sorted sequence being disjoint, and once it isn't, a third interval could overlap an entry
that is neither the immediate predecessor nor successor of its start key. **Detection:** add an
invariant-checking assertion (cheap, O(log n)) inside `book()` itself — after inserting,
re-verify that the newly inserted entry's predecessor and successor (now computed fresh) still
don't overlap it — which is exactly the check we already do before insertion, so this is "trust
but verify" rather than new logic. In production, a periodic background job that scans each
resource's full booking list for any pairwise overlap (O(n log n) per resource) would be a
reasonable correctness canary to run off the hot path.

### 2. Two threads crash mid-operation — not mid-lock-acquisition, but after acquiring all locks and partway through booking occurrence 3 of 5 onto 4 of 6 resources. What state are you left in, and is it recoverable?

In this in-memory design, a process crash loses all state equally — there's no "partway"
visible after restart, because nothing persisted. The more interesting failure mode is a
**thread** (not process) throwing mid-commit — e.g. an `OutOfMemoryError` during the loop that
calls `book()` on each resource. Because the commit-phase loop in `bookMeeting` calls
`calendarForRoom(...).book(...)` and `calendarForUser(...).book(...)` without a surrounding
try/catch that unwinds partial bookings, a thrown error mid-loop *would* leave some resources
booked and others not — a real gap in the current design, worth stating explicitly rather than
glossing over. The honest fix: since Phase 1 guarantees every `book()` call in Phase 2 cannot
throw `MeetingConflictException` (the only expected failure mode), the only way Phase 2 fails is
an unexpected `Error`/bug — and the correct response to that is "crash loudly, don't try to
paper over it with partial-commit rollback logic that is itself hard to get right under
concurrency." In production, this is exactly what a database transaction (§12) solves for free:
either the whole multi-row insert commits or none of it does, with no application-level rollback
code required.

### 3. Your lock-ordering deadlock-freedom argument assumes every caller goes through `ResourceLockCoordinator.withLocks`. What happens the day someone adds a second code path that acquires these same per-resource locks directly?

The deadlock-freedom proof is a *protocol* guarantee, not a *type-system* guarantee — nothing in
the code stops a future contributor from calling `lockFor(key).lock()` directly, bypassing the
sorted-ordering discipline, and reintroducing deadlock risk. This is a real, acknowledged
weakness of lock-based designs generally. Mitigations: (a) keep `lockFor` and the raw
`ConcurrentHashMap<String, ReentrantLock>` `private`, as done here, so there is no accessible
seam to bypass through normal use of the class; (b) in a larger codebase, this would warrant a
code-review checklist item or a static-analysis rule flagging any `ReentrantLock` usage outside
`ResourceLockCoordinator`; (c) the more robust long-term fix is to make `SchedulerService` the
*only* class that ever constructs resource keys and calls `withLocks`, which it already is in
this design — the discipline is enforced by "there is exactly one caller," not by the type
system, which is an honest limitation to name if pressed.

### 4. Why `ReentrantLock` per resource and not `synchronized` blocks?

`synchronized` cannot express a timeout — `tryLock(timeout, unit)` is essential to this design's
"fail fast under contention rather than block forever" guarantee (§11), and there is no
`synchronized` equivalent. `ReentrantLock` also supports fairness (`new ReentrantLock(true)`,
used here), which `synchronized` does not guarantee — under sustained contention on a single hot
resource, a fair lock prevents thread starvation (one unlucky thread perpetually losing the race
to acquire).

### 5. Your free-slot finder flattens every busy interval from every attendee into one list and sorts. For a meeting with 500 attendees, each with a dense calendar, is this still a reasonable approach?

With 500 attendees and, say, an average of 20 busy intervals per attendee in the search window,
n = 10,000, and O(n log n) ≈ 10,000 × 13 ≈ 130,000 comparisons — trivially fast, sub-millisecond
territory. The approach degrades gracefully; there's no pathological blowup. The one real
concern at that scale isn't the sort, it's the *collection* step: calling
`occurrencesOverlapping` once per attendee is 500 separate `TreeMap` range queries. That's still
fine (each is O(log n + k)), but if this were a hot, frequently-called endpoint (e.g. a live
"find a slot" UI that recomputes on every keystroke as attendees are added), batching those
range queries or caching per-attendee busy-interval snapshots for the duration of one search
session would be the next optimization — not changing the core algorithm.

### 6. You chose all-or-nothing booking for recurring meetings. Walk me through exactly what observable behavior a client sees when occurrence 7 of 10 conflicts — and is that behavior race-condition-safe?

The client sees a single `MeetingConflictException` thrown from `bookMeeting`, carrying the
specific resource key and the two conflicting intervals (occurrence 7's requested interval and
whatever existing booking it collided with) — see `MeetingConflictException`'s fields. Crucially,
*none* of the 10 occurrences are booked, including occurrences 1-6 which had no conflict —
because Phase 1 validates every occurrence against every resource before Phase 2 commits
anything (§9/§11). This is race-condition-safe because the entire validate-then-commit sequence
runs inside the locks for every resource the series touches, held for the whole operation — no
other thread can insert a new booking between Phase 1's validation of occurrence 3 and Phase 2's
commit of occurrence 1 that would invalidate an already-validated occurrence, because that other
thread cannot acquire any of the same locks until this operation releases them all.

### 7. If I ask you to add "optional attendees" (a meeting is bookable even if only optional attendees conflict) without changing `SchedulerService`'s existing code, can you?

Not without *some* change, but the change is additive and narrow, which is the right bar (Open
for extension, not literally zero-diff). Today, `BookingRequest.attendeeIds()` is a flat,
undifferentiated set — every attendee is "required." Adding optional attendees means: (a)
`BookingRequest` gains a second set, `optionalAttendeeIds`; (b) in `bookMeeting`'s Phase 1
validation loop, conflicts against optional attendees' calendars are collected but don't throw —
they become part of the `BookingResult` as "booked, but these optional attendees are busy" data
instead of a hard failure. No existing *behavior* for required attendees changes, no existing
test breaks, and `IntervalCalendar`/`ResourceLockCoordinator`/`RecurrenceExpansionService` are
completely untouched — the change is isolated to `BookingRequest`, `BookingResult`, and the
single validation loop in `SchedulerService`. This is a reasonable, bounded answer to give live
rather than claiming either "trivial, zero changes" (false) or "requires a redesign" (also
false).

### 8. Your `Meeting.anchorZone` is captured once at booking time specifically to avoid DST/timezone drift on recompute. What's the one scenario where this design choice itself causes a *different* bug?

If the organizer permanently relocates (e.g. moves from New York to London) and wants their
*entire* recurring series to shift to the new local time-of-day, this design makes that
impossible without cancelling and rebooking the series — `anchorZone` is immutable by design.
That's a deliberate trade-off (correctness/predictability over a rare edit-in-place convenience),
and it mirrors how Google Calendar actually behaves in practice (moving a recurring series to a
new timezone is a known rough edge in real calendar products, not just this exercise) — but it's
worth naming unprompted as a known, accepted limitation rather than waiting to be caught on it.

### 9. Walk me through what happens if `RecurrenceRule.MAX_OCCURRENCES` (730) is hit mid-expansion for a weekly rule with a generous `count` — does the client get a confusing partial result?

`WeeklyExpansionStrategy.expand` stops generating dates the moment `count >= maxOccurrences`
(the `break outer` in the day-of-week loop), so the returned list is simply shorter than the
rule's nominal `count` would otherwise produce — e.g. a rule asking for 1000 weekly occurrences
silently yields only 730. This is a real gap worth naming: the current implementation does not
surface *why* fewer occurrences than requested were returned. The honest fix: `BookingResult`
or a dedicated warning channel should communicate "requested N occurrences, capped at
MAX_OCCURRENCES" rather than silently truncating — a one-line addition (a boolean or count field
on `BookingResult`) that I'd flag as a near-term follow-up rather than something blocking this
design.

### 10. Is `TreeMapIntervalCalendar`'s per-method `synchronized` actually doing any useful work, given `ResourceLockCoordinator` already serializes all access to it?

Mostly defense-in-depth, and the question is fair — in the *current* call graph, every entry
point into a calendar's mutating methods goes through `SchedulerService`, which always holds the
resource's lock first. But `synchronized` here is cheap insurance against a future bug where
some new code path calls `calendar.book(...)` directly without going through the coordinator
(see Q3) — in that scenario, `synchronized` is the only thing standing between "wrong resource
locked" and "corrupted TreeMap," as opposed to "wrong resource locked" and merely "wrong but
internally consistent calendar." It is not redundant from a defense-in-depth perspective, even
though it is redundant in the currently-correct call graph.

### 11. How would you extend this design to support "find and book the first available room with capacity >= N for these 8 attendees" — a materially harder query than "is this specific room free?"

This needs a new query shape: iterate candidate rooms (filtered by `capacity >= N` via
`RoomRepository`), and for each, run the existing `findCommonFreeSlots`-style check (attendees +
that specific room) against the requested window, stopping at the first room with a qualifying
slot. This composes cleanly on top of existing primitives — no change to `IntervalCalendar`,
`TreeMapIntervalCalendar`, or `ResourceLockCoordinator` is needed, because "is this room+these
attendees free in this window" is already exactly what `findCommonFreeSlots` computes; the new
logic is purely a `SchedulerService`-level orchestration loop over a room repository query,
which is the right layer for it (a facade-level concern, not a DSA-level one).

### 12. If this system had 10 million users, what's the first thing that breaks, and what's your first fix?

The first thing that breaks is the **in-process lock registry and in-memory calendars
themselves** — a single JVM holding every user's and every room's calendar in a
`ConcurrentHashMap` does not scale horizontally at all; you cannot run more than one scheduler
instance and have them agree on the same locks. The first fix is *not* more clever in-process
data structures — it's moving the double-booking invariant into the database (the exclusion
constraint from §12) and making the calendars demoted to a cache, so correctness no longer
depends on a single process being the sole writer. The second thing that breaks, once sharded by
room/user, is a meeting with attendees spread across many shards (e.g. a cross-team all-hands
with 200 attendees on different database shards) — that's a distributed-transaction problem, and
the honest answer there is a two-phase-commit-style saga or accepting eventual consistency with
compensation (cancel-on-partial-failure) rather than true cross-shard atomicity, which is a real
unsolved complexity this design does not currently address and shouldn't pretend to.

### 13. You said "no distributed consensus needed" — defend that against someone who says "but what if the database itself needs to be highly available across regions?"

Multi-region availability for the *database* is a separable concern from "does this specific
booking decision need consensus." A regional, single-primary database (or a quorum-write system
like CockroachDB/Spanner, which already solves consistent writes without the application
needing its own Raft implementation) gives you the exclusion-constraint guarantee *and*
multi-region durability, without the scheduling service itself implementing any consensus
protocol. The distinction that matters: "do I need distributed consensus" is really "do I need
the booking decision for one room to be linearizable across every possible writer," and the
answer to *that* is yes, which is exactly what a strongly-consistent database (not a
consensus library hand-rolled into the application) already provides as a primitive. Reaching
for Raft/Paxos directly in the scheduling service would be solving a problem the database layer
already solves better.

### 14. Your `BookingRequest.Builder` always adds the organizer to the attendee set. What's one subtle bug this could cause, and how does the current code handle it?

If a caller also explicitly adds the organizer via `.addAttendee(organizerId)`, the builder uses
a `Set`, so this is a harmless no-op (idempotent insertion) — not a bug. The more subtle question
is ordering: `BookingRequest`'s constructor adds the organizer to a `LinkedHashSet` *after*
copying the caller's attendees, so the organizer is not guaranteed to be first in iteration
order if the caller already included them explicitly earlier in a different position. This
doesn't affect correctness (the set semantics are what matter, not iteration order — nothing in
`SchedulerService` depends on attendee iteration order for correctness), but it's worth noticing
as an example of "LinkedHashSet gives insertion-order iteration, which is a readability nicety,
not a correctness mechanism here" — the kind of precise statement a staff engineer should be
able to make about their own code's exact guarantees.

### 15. What's the actual consistency model you're providing — linearizable, sequential, eventual? Be precise.

**Linearizable**, with respect to the specific invariant "no two overlapping bookings exist
simultaneously on the same resource" — within a single JVM process. Every booking/cancellation
that touches a given resource is serialized through that resource's `ReentrantLock`, so from any
external observer's perspective, operations on a single resource appear to take effect in some
total order consistent with real time (the definition of linearizability for that resource).
Across *different*, non-overlapping resources, there is no ordering guarantee or need for one —
operations on Room A and Room B are genuinely concurrent and independent, which is correct,
not a consistency gap. The system does **not** provide linearizability across multiple
*processes* (there is exactly one JVM's worth of locks) — that gap is explicitly what §12's
database-level exclusion constraint would close in production.

### 16. Why is `IntervalCalendar.findConflict` a separate method from `book`, rather than `book` just being the only entry point that internally conflict-checks?

Because `SchedulerService.bookMeeting` needs to validate *every* occurrence of a recurring
meeting against *every* involved resource **before** committing *any* of them (the all-or-nothing
guarantee, Q6) — that requires a pure, non-mutating check (`findConflict`) usable in a loop,
separate from the mutating `book`. If `book` were the only entry point, achieving all-or-nothing
semantics would require either (a) speculatively booking and rolling back on conflict (exactly
the rollback complexity the current split avoids, see Q2), or (b) `book` itself taking a
"dry-run" flag — which is a worse API than two separate, clearly-named methods with distinct
single responsibilities.

### 17. Your `RecurrenceRule` disallows an unbounded recurrence (must specify `until` or `count`). What real product requirement would force you to relax that, and what would you do instead?

A product requirement like "recurring meetings should just continue indefinitely until someone
explicitly cancels the series, with no upfront end date" — a legitimate, common UX (Google
Calendar's default "weekly, no end date" option) that this design's validation currently
forbids. The fix is **not** to actually allow unbounded expansion (that reintroduces the
exact resource-exhaustion risk `MAX_OCCURRENCES` exists to prevent) — it's to change what
"unbounded" *means* operationally: generate and materialize occurrences lazily, a bounded
rolling window at a time (e.g. "the next 90 days," regenerated periodically by a background
job as time advances), rather than trying to expand "forever" eagerly at booking time. This is a
real architectural shift (from eager, complete expansion to lazy, windowed expansion) and I'd
flag it explicitly as a bigger change than it first sounds, not a one-line fix.

### 18. If you had exactly 2 weeks to ship a simplified version of this, what would you cut, and what would you absolutely refuse to cut?

**Absolutely keep:** the `TimeInterval` half-open-overlap invariant and the per-resource
conflict-check data structure — this is the correctness core; shipping a version that
occasionally double-books a room is a worse outcome than shipping late. Also keep per-resource
locking with sorted-order acquisition — the alternative (no locking, "we'll fix races later") is
how real production incidents happen.
**Cut first:** recurrence entirely (ship one-off meetings only, in week 1) — it's valuable but
separable, and every other requirement (conflict detection, concurrency safety, free-slot
search) works identically whether or not recurrence exists. **Cut second:** the common-free-slot
search feature — valuable UX, but the product is still usable (just less convenient) without
it. **Cut third:** cancellation scopes beyond "cancel the whole thing" — "this and following" /
"single occurrence" nuance can follow in a fast-follow release once one-off cancellation works.

### 19. Your `ResourceLockCoordinator` holds locks for the *entire* critical section, including the `MeetingRepository.save()` call. If that repository were a slow network call to a database (not the in-memory map it is today), what would that do to throughput, and how would you fix it?

It would be a serious problem: holding a resource's lock across a slow I/O call means every
other booking attempt for that same resource blocks for the full duration of that I/O, instead
of just the microseconds a `TreeMap` lookup takes — exactly the "lock hold time, not locking
strategy, is what matters for throughput" point from §11, inverted into a warning. The fix:
separate the fast, in-memory conflict-check-and-reserve step (still under the lock) from the
slow, durable-persistence step (ideally outside the lock, or performed via a fast local
write-ahead log that's asynchronously flushed to the real database). This is precisely the
argument for why, in a real production system, the exclusion-constraint approach (§12) is
better than application-level locking around a database call: the database's own MVCC/locking
mechanism already handles "hold the minimum necessary lock for the minimum necessary time"
far better than a coarse-grained application lock wrapped around a network round-trip.

### 20. You test the double-booking race with 16 threads and 5 repetitions. Why is that sufficient evidence, and what would make you *more* confident still?

It's reasonably strong evidence (not proof) because: (a) `CountDownLatch`-synchronized start
maximizes the probability all 16 threads genuinely contend at the same instant, rather than
trickling in sequentially and never actually racing; (b) repeating 5 times guards against a
single lucky/unlucky scheduling interleaving masking a real bug; (c) the assertion is strict
(`exactly 1` succeeds, `exactly 15` conflict) — a weaker assertion like "at least 1 succeeds"
would not catch a bug that let 2 threads both win. What would increase confidence further: running
the same test under a tool like `jcstress` (purpose-built for exposing JMM-level concurrency
bugs via many more interleavings and hardware-memory-model-aware testing) or running the test
suite repeatedly under heavy background CPU load / with `-Xint` (interpreted mode, which changes
thread-scheduling timing characteristics) to surface interleavings less likely under a JIT'd,
lightly-loaded test run. None of this is implemented here — a reasonable, explicitly-scoped
limitation for an LLD exercise, not a gap I'd leave unexamined in a real production concurrency
primitive.

### 21. Concurrent modification: two threads both try to cancel the *same* occurrence at the same time. What happens, and is it correct?

Both threads acquire the exact same resource locks (same meeting → same room + same attendees),
so they're fully serialized relative to each other by `ResourceLockCoordinator` — there's no
race here by construction. The first thread to acquire the locks finds the occurrence in
`toCancel` (it's currently booked), cancels it, and adds the exception date. The second thread,
once it acquires the locks after the first releases them, recomputes `occurrencesOverlapping`
and finds the date is now an exception — `toCancel` for that date comes back empty — and
`cancelMeeting` throws `InvalidMeetingRequestException` ("No matching occurrence(s) found").
This is correct and arguably the *right* user-facing behavior (a second "cancel this occurrence"
click doesn't silently succeed twice or throw a confusing error) — but it does mean the error
message doesn't distinguish "you asked for a date that was never part of this series" from "you
asked for a date that *was* part of this series but someone already cancelled it" — a UX
refinement worth naming rather than silently living with.

### 22. Why didn't you make `Meeting` itself responsible for checking conflicts (e.g. `meeting.conflictsWith(other)`), keeping the logic closer to the domain object?

Because conflict detection is fundamentally a *resource-scoped, multi-meeting* query — "does
this candidate interval conflict with anything already booked on this room" — not a pairwise
comparison between two `Meeting` instances. A `Meeting.conflictsWith(Meeting other)` method
would need the caller to already know which specific other meeting to compare against, which is
exactly the O(n) "check against everything" problem §10 is designed to avoid. Putting conflict
detection on `IntervalCalendar` instead keeps the O(log n) algorithmic win intact and keeps
`Meeting` a plain data-and-identity entity, which is the correct division of responsibility:
`Meeting` models "what was agreed to," `IntervalCalendar` models "the searchable index of what's
booked," and those are different concerns with different natural data structures.

### 23. How would backward compatibility work if you needed to change `TimeInterval` from half-open `[start, end)` to closed `[start, end]` semantics for some new resource type?

This would be a genuinely risky change if done globally — every existing conflict check,
free-slot computation, and back-to-back-meeting assumption in the system depends on half-open
semantics (§5 invariant #1, tested explicitly by `touchingBoundaryIsNotOverlap`). The correct
approach is **not** to change `TimeInterval` itself, but to introduce the closed-interval
semantics as a property of the specific new resource type's conflict check (e.g. a
`strictBoundary` flag passed to `IntervalCalendar`, or a different `IntervalCalendar`
implementation entirely for that resource type) — leaving the shared `TimeInterval` value
object and its single, well-tested `overlaps` definition untouched for every existing resource.
Silently changing a core value object's semantics system-wide to satisfy one new resource type
would be exactly the kind of change that passes code review locally and breaks a dozen
assumptions elsewhere.

### 24. What's the hardest-to-reverse architectural decision in this design, and why?

The half-open `[start, end)` interval semantics baked into `TimeInterval.overlaps`. Every other
decision in this system — the locking strategy, the recurrence model, even the choice of
`TreeMap` vs. an interval tree — can be swapped out behind their respective interfaces without
touching callers elsewhere. But `TimeInterval`'s overlap definition is load-bearing *everywhere*:
every conflict check, every free-slot gap computation, and every test's expected values are
written against this one definition. Changing it later (even to closed-interval semantics for a
legitimate reason) requires re-auditing every call site that constructs or compares intervals,
and re-deriving the correctness argument for the `TreeMap` neighbour-check trick (§10), which
explicitly depends on half-open semantics for its predecessor/successor proof. This is the one
decision I'd spend the most up-front time getting right, precisely because it's the most
expensive to revisit later.
