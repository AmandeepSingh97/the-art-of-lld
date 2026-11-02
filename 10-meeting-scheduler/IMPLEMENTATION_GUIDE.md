# Implementation Guide

A practical, whiteboard/live-coding-oriented guide to actually *building* this system under
interview time pressure — not a restatement of the architecture, but a sequencing and
narration playbook.

---

## 1. What to Write First, and Why

Write in this exact order. Each step produces something that compiles and is independently
demonstrable, so you always have a working checkpoint to retreat to if time runs out.

1. **`TimeInterval`** — the one type everything else depends on. Get its `overlaps()` semantics
   right first (half-open `[start, end)`), because every other class's correctness depends on
   this single method's definition. This is 10 minutes well spent before writing anything else.
2. **ID value objects** (`UserId`, `RoomId`, `MeetingId`) — trivial, but unblock every signature
   you're about to write. Do these fast, don't over-narrate them.
3. **`Room`, `User`, `Meeting` (no recurrence yet)** — minimal entities: id, name, and for
   `Meeting`, organizer + attendees + room + interval + status. Recurrence fields come later.
4. **`IntervalCalendar` interface + `TreeMapIntervalCalendar`** — this is the DSA centerpiece and
   the part the interviewer most wants to see you reason through out loud. Build `findConflict`
   first (read-only, easiest to test with print statements), then `book`, then `cancel`.
5. **`SchedulerService` (one-off meetings only, no locking yet)** — wire `bookMeeting` to call
   `findConflict` then `book` on each resource's calendar. This is your first fully working,
   demonstrable vertical slice — stop here and run a demo if you're at the 15-20 minute mark.
6. **Concurrency (`ResourceLockCoordinator`)** — add *after* the single-threaded path works, not
   before. Trying to get locking right before the business logic is right is a common
   time-wasting mistake.
7. **Recurrence (`RecurrenceRule`, expansion strategies)** — only after one-off booking and
   conflict detection are solid. This is the first thing to cut if time is short (see §7 below).
8. **Free-slot search (`FreeSlotFinder`)** — last, since it's additive and doesn't touch the
   conflict-detection or booking core at all.

## 2. Minimal Compilable Implementation Outline

If you need to type fast and get something running, this is the smallest version that
demonstrates the core idea end-to-end (one-off meetings, no recurrence, single-threaded):

```java
// TimeInterval.java
public record TimeInterval(Instant start, Instant end) {
    public TimeInterval {
        if (!start.isBefore(end)) throw new IllegalArgumentException("start must precede end");
    }
    public boolean overlaps(TimeInterval other) {
        return this.start.isBefore(other.end) && other.start.isBefore(this.end);
    }
}

// IntervalCalendar.java (minimal)
public class SimpleCalendar {
    private final TreeMap<Instant, TimeInterval> booked = new TreeMap<>();

    public synchronized Optional<TimeInterval> findConflict(TimeInterval candidate) {
        var floor = booked.floorEntry(candidate.start());
        if (floor != null && floor.getValue().overlaps(candidate)) return Optional.of(floor.getValue());
        var ceiling = booked.ceilingEntry(candidate.start());
        if (ceiling != null && ceiling.getValue().overlaps(candidate)) return Optional.of(ceiling.getValue());
        return Optional.empty();
    }

    public synchronized void book(TimeInterval interval) {
        if (findConflict(interval).isPresent()) throw new IllegalStateException("conflict");
        booked.put(interval.start(), interval);
    }
}
```

This ~25-line core already demonstrates the single most important idea in the whole problem:
O(log n) conflict detection via sorted neighbour-checking. Everything else (users, rooms,
recurrence, locking, free-slot search) layers on top of this nucleus.

## 3. Suggested Implementation Sequence (Timed)

For a 45-minute session, roughly:
- **0-5 min:** clarify requirements, state assumptions out loud (see `INTERVIEW_PLAYBOOK.md`).
- **5-10 min:** `TimeInterval` + ID types.
- **10-20 min:** `IntervalCalendar`/`TreeMapIntervalCalendar` with the neighbour-check, narrating
  the O(log n) argument as you write `findConflict`.
- **20-30 min:** `Meeting`, `Room`, `User`, and a minimal `SchedulerService.bookMeeting` wiring
  conflict-check + booking together for one-off meetings. Demo this working.
- **30-38 min:** add per-resource locking (`ResourceLockCoordinator`), narrating the
  sorted-key-order deadlock-freedom argument.
- **38-45 min:** sketch (don't fully implement) recurrence and free-slot search verbally, or
  implement a minimal daily-only recurrence if time allows — see §7 for exactly what to cut.

## 4. Important Methods to Get Right

These are the methods where a subtle bug is most likely to surface under interviewer scrutiny —
spend disproportionate care here:

- **`TimeInterval.overlaps`** — must use strict `<` (not `<=`) on both sides to get half-open
  semantics right; getting this wrong either rejects legitimate back-to-back meetings or allows
  genuine double-bookings. State the half-open choice out loud *before* writing the method.
- **`TreeMapIntervalCalendar.findConflict`** — must check *both* `floorEntry` and `ceilingEntry`,
  not just one. A common mistake is checking only the floor (assuming "nothing starts after
  mine could possibly overlap, since mine conflicts would be with earlier things") — wrong,
  because a shorter existing booking that starts after the candidate's start but still before
  the candidate's end is exactly the ceiling case.
- **`SchedulerService.bookMeeting`'s two-phase split** — validate every occurrence/resource pair
  *before* committing any of them. Writing this as a single loop that books-as-it-checks is the
  single most common correctness bug in a live implementation of this problem (produces partial,
  inconsistent bookings on conflict).
- **`ResourceLockCoordinator.withLocks`'s sorted acquisition + reverse release in `finally`** —
  the `finally` block is not optional; skip it and a `SchedulingTimeoutException` partway through
  acquisition leaks the locks already held, causing every subsequent booking on those resources
  to hang.
- **`WeeklyExpansionStrategy`'s date-walking loop** — must correctly handle a series whose first
  occurrence doesn't align with the first matching day-of-week in the walk (e.g. series starts
  on a Wednesday but recurs on Mon/Wed/Fri) — a common bug is assuming the start date is always
  itself a matching day.

## 5. Common Coding Mistakes (and how to avoid them live)

1. **Using `<=`/`>=` instead of strict `<`/`>` in overlap checks** — silently turns
   back-to-back meetings into false conflicts. Fix by stating the half-open invariant before
   coding, then writing a one-line mental test case (`[9,10)` vs `[10,11)` → no conflict) before
   trusting the implementation.
2. **Checking only one TreeMap neighbour** — always check both floor and ceiling; say this out
   loud as you write it so the interviewer sees you're aware of the edge case, not just
   guessing correctly.
3. **Booking as you validate, in one pass, for recurring series** — leads to partial commits on
   conflict. State the two-phase approach explicitly before coding it, even if you don't have
   time to fully implement recurrence, since the *reasoning* is what's being evaluated.
4. **Acquiring locks in request-order instead of sorted-key-order** — looks correct in testing
   with a single thread or low contention, but is a live deadlock risk; always sort resource keys
   before acquiring, and say why.
5. **Forgetting to release locks in a `finally` block** — easy to miss under time pressure; a
   `try { acquire-all } finally { release-all-held-so-far }` skeleton should be written before
   filling in the business logic inside it.
6. **Comparing `LocalDateTime` instead of `Instant`/`ZonedDateTime` across timezones** — a subtle
   bug that won't show up in a same-timezone demo but is exactly the kind of thing a staff-level
   interviewer will probe ("what if the two attendees are in different timezones?"). Default to
   `Instant` internally from the start, even in the minimal version.
7. **Not handling the "meeting with no room" case** (a pure attendee-only sync) — if your
   `bookMeeting` assumes `roomId` is always present and NPEs on a null, that's an easy embarrassing
   live bug; make `roomId` `Optional` or nullable-and-checked from the start.

## 6. How to Narrate Code While Writing It

- **Before writing `TimeInterval`:** "I'll make this half-open — `[start, end)` — so a meeting
  ending at 10:00 and one starting at 10:00 don't conflict; that matches how calendars actually
  behave day to day."
- **Before writing `findConflict`:** "Since I'll enforce that bookings on one resource never
  overlap, the existing bookings form a sorted, disjoint sequence by start time — so a new
  candidate can only possibly conflict with its immediate neighbours in that sorted order. I'll
  use a `TreeMap` so I can get those neighbours in O(log n) via `floorEntry`/`ceilingEntry`
  instead of scanning every existing booking."
- **Before writing the locking code:** "I'll acquire locks for every resource this booking
  touches, always in sorted key order, regardless of the order they appear in the request — that
  rules out deadlock structurally, since no two operations can ever be waiting on each other in
  a cycle."
- **While writing recurrence expansion:** "I'm keeping this as a Strategy per frequency, so
  adding monthly later doesn't touch the daily or weekly code, or anything that calls into this
  service."
- **General rule:** narrate the *decision* before the *code*, not after. Writing code silently
  and explaining afterward reads as rationalizing; stating intent first reads as design.

## 7. What Can Be Safely Omitted Under Time Pressure

In priority order — cut from the bottom up as time runs short:

1. **Recurrence entirely** — mention it as "I'd add a `RecurrenceRule` + Strategy-per-frequency
   expansion here, but let me focus on getting one-off booking and conflict detection fully
   correct first," and move on. This is the single highest-value thing to defer, because it's
   the most time-consuming to implement fully and least essential to demonstrating the core DSA
   and concurrency ideas.
2. **Free-slot search** — a nice-to-have on top of a working conflict-detection core; describe
   the merge-intervals algorithm verbally (sort busy intervals, merge overlaps, walk gaps) rather
   than coding it, unless time clearly remains.
3. **Cancellation scopes (single/this-and-following/all)** — implement only "cancel the whole
   meeting" if time is short; mention the scope distinction verbally.
4. **The `AugmentedIntervalTree` alternative** — never needed for a working solution; only bring
   it up if directly asked "what if you needed to find *all* overlapping meetings, not just
   detect one conflict."
5. **Observer/event-listener plumbing** — purely additive; skip unless asked about
   notifications/integrations explicitly.
6. **Builder pattern on `BookingRequest`** — a plain constructor with a few parameters is fine for
   a live-coding minimal version; the Builder is a polish item, not a correctness item.

**Never cut**, even under severe time pressure: the half-open interval overlap check, and some
articulated form of conflict detection before allowing a booking — these are the problem's core
and cutting them means you haven't solved the stated problem at all.

## 8. A Simplified Version Implementable in 20-30 Minutes

Scope: one-off meetings only (no recurrence), single room per meeting, no explicit
multi-threading code (but verbally describe how you'd add it), in-memory storage, no free-slot
search.

```java
import java.time.Instant;
import java.util.*;

record TimeInterval(Instant start, Instant end) {
    TimeInterval {
        if (!start.isBefore(end)) throw new IllegalArgumentException("start must precede end");
    }
    boolean overlaps(TimeInterval other) {
        return this.start.isBefore(other.end) && other.start.isBefore(this.end);
    }
}

record Meeting(String id, String title, String roomId, TimeInterval interval) {}

class RoomCalendar {
    private final TreeMap<Instant, Meeting> booked = new TreeMap<>();

    Optional<Meeting> findConflict(TimeInterval candidate) {
        var floor = booked.floorEntry(candidate.start());
        if (floor != null && floor.getValue().interval().overlaps(candidate)) return Optional.of(floor.getValue());
        var ceiling = booked.ceilingEntry(candidate.start());
        if (ceiling != null && ceiling.getValue().interval().overlaps(candidate)) return Optional.of(ceiling.getValue());
        return Optional.empty();
    }

    void book(Meeting meeting) {
        if (findConflict(meeting.interval()).isPresent()) {
            throw new IllegalStateException("Conflict with an existing meeting on this room");
        }
        booked.put(meeting.interval().start(), meeting);
    }

    void cancel(Meeting meeting) {
        booked.remove(meeting.interval().start());
    }
}

class SimpleSchedulerService {
    private final Map<String, RoomCalendar> calendarsByRoom = new HashMap<>();
    private int nextId = 1;

    Meeting bookMeeting(String title, String roomId, TimeInterval interval) {
        RoomCalendar calendar = calendarsByRoom.computeIfAbsent(roomId, r -> new RoomCalendar());
        Meeting meeting = new Meeting("m" + (nextId++), title, roomId, interval);
        calendar.book(meeting); // throws IllegalStateException on conflict
        return meeting;
    }

    void cancelMeeting(Meeting meeting) {
        calendarsByRoom.get(meeting.roomId()).cancel(meeting);
    }
}

public class Demo {
    public static void main(String[] args) {
        SimpleSchedulerService scheduler = new SimpleSchedulerService();
        Instant t0 = Instant.parse("2025-06-02T09:00:00Z");

        Meeting m1 = scheduler.bookMeeting("Standup", "room-1",
                new TimeInterval(t0, t0.plusSeconds(1800)));
        System.out.println("Booked: " + m1);

        try {
            scheduler.bookMeeting("Overlapping sync", "room-1",
                    new TimeInterval(t0.plusSeconds(900), t0.plusSeconds(2700)));
        } catch (IllegalStateException e) {
            System.out.println("Correctly rejected conflicting booking: " + e.getMessage());
        }

        Meeting m2 = scheduler.bookMeeting("Back-to-back", "room-1",
                new TimeInterval(t0.plusSeconds(1800), t0.plusSeconds(3600)));
        System.out.println("Booked back-to-back without conflict: " + m2);
    }
}
```

This compiles standalone (single file, no external dependencies), runs, and demonstrates: the
half-open interval invariant, O(log n) neighbour-based conflict detection via `TreeMap`, correct
rejection of an overlapping booking, and correct acceptance of a legitimate back-to-back booking
— the minimum viable proof of the core idea, in roughly 60 lines, achievable well within 30
minutes including narration.

**What to say if asked "how would you add concurrency safety to this simplified version?":**
"I'd wrap the `book` call for a given room in a `ReentrantLock` keyed by `roomId`, acquired with
a timeout rather than blocking indefinitely, and — if a single operation touches multiple rooms
or attendees — acquire all the needed locks in a fixed sorted order first to avoid deadlock,
exactly like `ResourceLockCoordinator` in the full version." Saying this out loud, even without
coding it, demonstrates the concurrency reasoning the interviewer is actually evaluating.
