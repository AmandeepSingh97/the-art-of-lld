# Interview Playbook — Meeting Scheduler

A speakable, time-boxed playbook for actually conducting this interview, not just a repeat of
the README's technical content.

---

## 1. How to open the discussion

Start by restating the problem in your own words and immediately proposing scope, rather than
diving into classes:

> "So we're designing a meeting scheduler — users can book rooms and invite other people for a
> time range, and the system has to guarantee nobody double-books a room or a person. I'd like
> to also support recurring meetings and finding a common free slot across a group. Before I
> start modelling, I have a handful of scoping questions — can I ask those first?"

This signals: you understand the problem, you have a plan, and you're not going to design in a
vacuum.

## 2. First 5 clarifying questions to ask

1. "Can a single room ever have more than one legitimate simultaneous booking — like
   open-seating capacity — or is it always strictly one meeting at a time?" (This is the single
   biggest fork — see README §3.1.)
2. "How far do we need to go on recurrence — just daily/weekly, or do we need monthly/yearly
   patterns too?"
3. "If I'm booking a recurring meeting and only one occurrence out of ten conflicts, should I
   book the other nine, or reject the whole series?"
4. "Should I assume a single-process in-memory system for this exercise, or do you want me to
   talk about how it'd be distributed?"
5. "Roughly what scale are we talking — tens of attendees per meeting, thousands of bookings
   per room over its lifetime? That affects which data structure I'd reach for."

## 3. Requirements to establish

State these out loud before coding, so the interviewer can correct you early:
- Book one-off and recurring (daily/weekly) meetings with a title, organizer, attendees, and
  optional room.
- Reject a booking if the room or any attendee is already busy.
- Cancel a single occurrence, this-and-following, or the whole series.
- Find common free slots across a group of attendees.
- No double-booking, ever, even under concurrent requests.
- Timezone-correct — assume users are in different zones.

## 4. How to identify core entities

Walk from nouns in the problem statement to candidate entities, then immediately classify each
as entity vs. value object out loud:

> "User and Room are entities — they have identity and a lifecycle. A time range is a value
> object — two intervals with the same start and end are interchangeable, so I'll model it as
> an immutable record, not a class with identity. Meeting is the interesting one — it's the
> aggregate root tying organizer, attendees, room, time, and recurrence together."

Then introduce `TimeInterval` as the foundational value object early — everything else
(conflict detection, free-slot search) is built on it, and saying this out loud shows you're
building from a stable foundation rather than improvising structure later.

## 5. How to explain class relationships

Describe ownership vs. reference explicitly:

> "A Meeting references a RoomId and a set of UserIds — it doesn't own those entities, they
> have their own independent lifecycle and repository. But a Meeting owns its TimeInterval and
> RecurrenceRule — those are value objects created and destroyed with the meeting itself."

Then introduce the one subtlety that shows depth: occurrences are *derived*, not stored:

> "I'm not going to store a list of occurrence dates on the Meeting — I'll recompute them
> deterministically from the base interval plus the recurrence rule plus any cancelled
> exception dates. That way there's exactly one source of truth and no risk of the two drifting
> apart."

## 6. How to draw the architecture

Draw top-down, in this order, narrating as you go:
1. A `SchedulerService` facade box at the top — "this is the only thing a client talks to."
2. Below it, three boxes: per-resource calendars (the conflict-detection engine), a recurrence
   expansion service, and a free-slot finder — "these are the three algorithmic cores, and
   they're independent of each other."
3. A locking coordinator box with arrows into the calendars — "this is what makes multi-resource
   booking atomic."
4. Repositories off to the side — "swappable for a real database without touching anything
   above."

Keep it to one whiteboard-sized diagram; don't draw every class.

## 7. Which interfaces to create first

In order:
1. `TimeInterval` (as a record, not strictly an "interface," but it's the foundational
   contract everything else depends on — build it first).
2. `IntervalCalendar` — the conflict-detection seam. Get this interface right before writing
   any implementation; it's what makes the DSA discussion (§10 of the README) concrete.
3. `RecurrenceExpansionStrategy` — only after the interviewer confirms recurrence is in scope.
4. Repository interfaces (`UserRepository` etc.) — quick, mechanical, do them once you need
   persistence, not before.

## 8. Which classes to implement first

1. `TimeInterval` with `overlaps`/`contains` — get this right and tested before anything else;
   every later bug you'd hit is a `TimeInterval` edge case (boundary-touching, in particular).
2. `TreeMapIntervalCalendar` — the conflict-detection core. Implement and verbally prove the
   two-neighbour-check argument before moving on.
3. `User`, `Room`, `Meeting` — straightforward entity classes.
4. `SchedulerService.bookMeeting` for the one-off (non-recurring) case first — get a single
   booking + conflict rejection working end-to-end before adding recurrence.
5. `RecurrenceRule` + `RecurrenceExpansionService` — add recurring bookings once one-off works.
6. `ResourceLockCoordinator` — add real concurrency safety once the single-threaded logic is
   correct; don't try to design locking and conflict detection simultaneously.
7. `FreeSlotFinder` — last, since it's the most self-contained, easiest to defer if time runs
   short.

## 9. Which design patterns to introduce and when

- Mention **Strategy** naturally when you get to recurrence: "daily and weekly are different
  enough algorithms that I'll make this a Strategy interface rather than a switch statement — if
  we add monthly later, it's a new class, not a new branch in existing code."
- Mention **Builder** naturally when you define `BookingRequest`: "this has enough optional
  fields that I'll give it a builder rather than a constructor with five optional parameters."
- Mention **Facade** naturally when you introduce `SchedulerService`: "I want exactly one entry
  point so callers can never bypass the locking protocol by talking to a calendar directly."
- Mention **Observer** only if there's time left, when discussing notifications — don't force it
  in early; it's a "nice to have I'd add if asked about notifications," not core.
- **Explicitly say what you're not doing and why** — e.g. "I'm not using Singleton for the
  scheduler service, I'd just construct it once in the app's composition root; a singleton here
  would only make testing harder." This proactive restraint reads as seniority.

## 10. How to discuss concurrency

Don't wait to be asked — raise it yourself once the single-threaded design is solid:

> "Now, the critical safety property here is no double-booking under concurrent requests. Let
> me walk through the race condition first, then how I prevent it."

Then, in order:
1. State the race concretely (two threads, overlapping resource sets, TOCTOU).
2. Propose per-resource locks (not a global lock) — justify the granularity choice.
3. Immediately raise the deadlock risk yourself: "since one booking can touch multiple
   resources — a room and several attendees — I need to be careful about lock ordering or I can
   deadlock." Then describe the sorted-key-acquisition fix.
4. Mention the timeout: "I'll use tryLock with a timeout rather than blocking forever, so a
   stuck thread fails fast instead of hanging."
5. If asked "how would you test this claim," describe (or write) a test that launches multiple
   threads racing for the same slot and asserts exactly one wins — this is the single strongest
   signal you can give that you actually understand the guarantee, not just the vocabulary.

## 11. How to discuss edge cases

Have these ready without prompting:
- Back-to-back meetings (one ends exactly when another starts) — must NOT conflict. State the
  half-open interval design decision here.
- A recurring meeting where only one occurrence conflicts — state your all-or-nothing decision
  and why.
- Cancelling a single occurrence of a recurring series vs. the whole series vs. "this and
  following" — the classic three-way scope.
- A user's timezone changing after a recurring meeting is created — does the series shift?
  (Answer: no, if you anchor the zone at creation time — a subtle, staff-level detail worth
  raising unprompted.)
- An idempotent retry of a booking request after a network timeout — should not create a
  duplicate.

## 12. How to handle interviewer interruptions

- If asked "what if a room has capacity for multiple concurrent bookings" mid-design: don't
  panic and redesign everything live. Say: "That changes the core invariant my conflict-check
  relies on — let me describe the alternative data structure (a true interval tree) I'd use
  instead, and why it's a strictly more general, slightly more expensive structure," then sketch
  it at a high level rather than fully rewriting your `TreeMap`-based design.
- If asked to add a feature (e.g. "what about monthly recurrence?") after you've already built
  the Strategy interface: this is good news — just say "that's exactly why I made this a
  Strategy — I add one new class, nothing else changes" and move on quickly.
- If challenged on a trade-off (e.g. "why not just use a database lock instead of in-process
  locks?"): acknowledge the alternative is valid in production, explain why you chose the
  simpler path for this exercise's scope, and reference the production-evolution discussion
  rather than getting defensive.

## 13. How to prioritise if time is running out

If you're at the 80% time mark and not finished, in priority order, keep:
1. `TimeInterval` + `IntervalCalendar`/`TreeMapIntervalCalendar` (conflict detection) — this
   *is* the problem; never cut it.
2. A working, single-threaded `bookMeeting` for one-off meetings.
3. The concurrency story *as a verbal explanation with a code sketch*, even if you don't finish
   implementing `ResourceLockCoordinator` — say "here's the race, here's the lock-ordering fix
   I'd apply, here's roughly what the code looks like" and move on.

Cut, in this order, if you must:
1. Recurrence expansion (describe the Strategy interface and the algorithm verbally instead of
   fully coding `WeeklyExpansionStrategy`'s day-of-week iteration).
2. Free-slot search (this is a nice-to-have relative to the core booking/conflict story).
3. Full exception hierarchy (a single `RuntimeException` subtype for "conflict" is enough to
   demonstrate the idea).

## 14. Three full walkthroughs with realistic timelines

### 30-minute walkthrough
- 0-3 min: restate problem, ask 2-3 clarifying questions, state scope out loud.
- 3-8 min: entities (`User`, `Room`, `Meeting`, `TimeInterval`), draw the one architecture box
  diagram.
- 8-18 min: implement `TimeInterval.overlaps` and `TreeMapIntervalCalendar` (book/findConflict),
  explaining the neighbour-check proof as you write it.
- 18-24 min: wire up a minimal `SchedulerService.bookMeeting` for one-off meetings; show a
  conflict being rejected.
- 24-28 min: verbally cover concurrency (the race, the fix) without necessarily coding the lock
  coordinator.
- 28-30 min: state what you'd add next (recurrence, free-slot search) and why you prioritized
  as you did.

### 45-minute walkthrough
- 0-5 min: scope + clarifying questions.
- 5-12 min: entities + architecture diagram + pattern call-outs (Strategy, Builder mentioned
  up front as planned, not yet implemented).
- 12-24 min: `TimeInterval`, `IntervalCalendar`/`TreeMapIntervalCalendar`, one-off
  `bookMeeting`, conflict rejection demo.
- 24-32 min: recurrence — `RecurrenceRule`, one `RecurrenceExpansionStrategy` implemented fully
  (e.g. daily), weekly described verbally if time is tight.
- 32-40 min: concurrency — implement `ResourceLockCoordinator`, explain sorted-order
  deadlock-freedom, sketch (or write) the multi-threaded test.
- 40-45 min: free-slot search described at the algorithm level (merge intervals), trade-offs
  recap.

### 60-minute walkthrough
- 0-5 min: scope + clarifying questions.
- 5-15 min: full entity/value-object modelling + domain invariants spoken out loud + full
  architecture diagram with all patterns named and justified.
- 15-28 min: `TimeInterval`, `IntervalCalendar`/`TreeMapIntervalCalendar` fully implemented and
  proven correct verbally; mention the `AugmentedIntervalTree` alternative and when it'd be
  needed instead.
- 28-38 min: one-off and recurring booking fully working, including the all-or-nothing
  validate-then-commit split.
- 38-48 min: concurrency fully implemented (`ResourceLockCoordinator`), deadlock argument
  stated precisely, a real multithreaded test written and run if the environment allows it.
- 48-55 min: free-slot search fully implemented (`FreeSlotFinder`), cancellation scopes
  discussed/implemented.
- 55-60 min: trade-offs recap, what you'd do differently in production (persistence,
  distributed locking), and open the floor for the interviewer's follow-ups.

## 15. What I would say to the interviewer

A set of natural, speakable sentences — not textbook language — for the moments that matter
most:

- *Opening scope:* "Before I write any code, let me just nail down what 'no double-booking'
  actually means here, because that assumption decides which data structure I reach for."
- *Introducing the TreeMap trick:* "Here's the thing I want to exploit: since a room can only
  have one meeting at a time, the bookings I already have are never overlapping with each other
  — they're just a sorted list of disjoint ranges. So for a new request, I only ever need to
  look at its immediate neighbor on either side in that sorted order, not the whole history."
- *On why not a naive list:* "I could store bookings in a list and just scan it every time, but
  that gets slower every single booking we ever make on a popular room — I'd rather pay a tiny
  bit more design complexity up front for something that stays fast at scale."
- *Introducing concurrency:* "Everything I've built so far is correct for one thread at a time.
  Let me now break it on purpose — here's the exact interleaving that causes a double-booking —
  and then fix it."
- *On the lock-ordering fix:* "The deadlock risk here is real, not theoretical — a meeting
  touches a room plus however many attendees, so two different bookings can genuinely want the
  same two locks in opposite orders. The fix is boring but it works: always sort the keys and
  always acquire in that order, every time, no exceptions."
- *On all-or-nothing recurring bookings:* "I made a call here that I want to flag rather than
  hide: if even one occurrence in a ten-meeting recurring series conflicts, I reject the whole
  series rather than booking the other nine. I think that's the safer default, but it's a real
  product decision, and I'd want to confirm it with whoever owns this feature."
- *When asked "how would this scale to millions of users":* "Honestly, the core algorithm
  doesn't need to change — what changes is where the lock lives. Today it's an in-process
  ReentrantLock; at scale I'd push that invariant into the database as an exclusion constraint
  so it holds even across multiple service instances, and shard the data by room/user. I
  wouldn't reach for a distributed consensus protocol here — that's solving a much harder
  problem than this one actually is."
- *Closing:* "That's the design — happy to go deeper on any piece, whether that's the locking
  proof, the recurrence algorithm, or trade-offs I made that you'd push back on."
