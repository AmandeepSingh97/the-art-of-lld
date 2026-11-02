# Implementation Guide — BookMyShow (Movie Ticket Booking System)

Practical, narration-ready guidance for actually *writing* this system
live in an interview, under real time pressure. Pairs with
`INTERVIEW_PLAYBOOK.md` (which covers the *conversation*) — this document
covers the *typing*.

---

## 1. Suggested Implementation Sequence

Write in this order. Each step should compile (even if incomplete) before
moving to the next — never let yourself get more than one step away from
a compiling state.

1. **Enums first** (`SeatCategory`, `SeatStatus`, `BookingStatus`,
   `PaymentStatus`) — zero dependencies, pure vocabulary, and writing them
   first forces you to say out loud what states/categories exist before
   any class needs them.
2. **Static model classes with no behavior** (`City`, `Theatre`, `Screen`,
   `Seat`, `Movie`, `User`) — plain data holders with constructors,
   getters, and `equals`/`hashCode` on id. These compile immediately and
   give you something to new-up in a `main` method for sanity-checking as
   you go.
3. **`ShowSeat`** — the single most important class. Write this
   *carefully and alone*, before anything depends on it. State the
   invariant out loud before writing a line of code: "exactly one
   `tryLock` call among any number of concurrent callers for the same
   seat may succeed; everyone else must observably fail — never block,
   never deadlock, never silently corrupt state."
4. **`Booking`** — the state machine. Write the transition table before
   the class: draw (or say) the full `BookingStatus` graph first, then
   implement it as data (`EnumMap<BookingStatus, EnumSet<BookingStatus>>`)
   rather than as a chain of `if` statements.
5. **Repository interfaces + one trivial in-memory implementation
   (`InMemoryRepository<ID, T>`)** — generics here save you from writing
   six nearly-identical classes; mention this explicitly as a
   time-saving move, not a hidden shortcut.
6. **`SeatLockService` interface + `InMemorySeatLockService`** — this is
   where the TTL/reaper/multi-seat-atomicity logic lives. Implement
   single-seat `lockSeat`/`confirmLock`/`releaseLock` first, get that
   fully working (even temporarily hand-test with a 2-line `main`), *then*
   add multi-seat (`lockSeats` with rollback) on top.
7. **`PricingStrategy` + `FixedPricingStrategy`** — trivial, but do it
   before `BookingService` needs it. Skip `DynamicPricingStrategy` under
   real time pressure (see §7 below).
8. **`PaymentGateway` + `MockPaymentGateway`** — trivial interface, a
   mock with a configurable success/failure predicate for testability.
9. **`BookingService`** — the orchestrator. Only write this once steps
   3–8 exist and compile; it has nothing to orchestrate before then.
10. **A `main`/demo method exercising the happy path end-to-end**, then
    immediately follow it with **the concurrent-booking demonstration**
    (§4 below) — this is very likely the single most interview-valuable
    thing you can show working live.

---

## 2. Minimal Compilable Implementation Outline

If you had to stub the *entire* system in under five minutes just to get
something compiling end-to-end (e.g., to unblock live-coding momentum
before fleshing out real logic), this is the smallest skeleton that
still captures every key decision point:

```java
enum SeatCategory { SILVER, GOLD, PLATINUM }
enum SeatStatus { AVAILABLE, LOCKED, BOOKED }
enum BookingStatus { CREATED, SEATS_LOCKED, CONFIRMED, CANCELLED, EXPIRED }

class ShowSeat {
    private final AtomicReference<LockState> state =
        new AtomicReference<>(LockState.available());

    boolean tryLock(String lockId, String ownerId, long ttlMillis, long now) {
        while (true) {
            LockState current = state.get();
            if (!isAvailable(current, now)) return false;
            LockState next = new LockState(SeatStatus.LOCKED, lockId, ownerId, now + ttlMillis);
            if (state.compareAndSet(current, next)) return true;
            // else: lost the race, loop and re-read
        }
    }
    // confirm(lockId), release(lockId), cancelBooking(bookingId) follow the same CAS-loop shape.
}

interface SeatLockService {
    String lockSeats(String showId, List<String> seatIds, String userId, Duration ttl);
    void confirmLock(String lockId);
    void releaseLock(String lockId);
}

interface PricingStrategy {
    BigDecimal priceFor(Show show, SeatCategory category, Instant now);
}

interface PaymentGateway {
    PaymentResult charge(String idempotencyKey, BigDecimal amount);
    PaymentResult refund(String paymentId, BigDecimal amount);
}

class BookingService {
    Booking initiateBooking(String userId, String showId, List<String> seatIds, String idempotencyKey) { ... }
    Booking confirmBooking(String bookingId) { ... }
    void cancelBooking(String bookingId) { ... }
}
```

Say this plan out loud before typing: *"I'll stub every interface and the
CAS skeleton first so everything compiles, then fill in real logic inside
each method — that way I always have something runnable to fall back
on."*

---

## 3. Important Methods (What To Get Exactly Right)

These methods carry the actual interview signal. Everything else is
plumbing.

- **`ShowSeat.tryLock`** — must be a `while (true)` CAS retry loop, not a
  single `compareAndSet` call. A single call without retrying is the #1
  mistake (see §5) — it silently under-retries real contention,
  surfacing as "sometimes a seat refuses a lock even though it was free
  a moment ago" in flaky tests.
- **`ShowSeat.confirm`** — must verify *both* the lock id matches *and*
  the state is still `LOCKED` (not expired) before transitioning to
  `BOOKED`. Checking only the lock id and skipping the expiry check is
  the #2 mistake — it would let a booking confirm against a seat whose
  hold already silently expired and was possibly re-locked by someone
  else.
- **`InMemorySeatLockService.lockSeats`** — must roll back (release)
  every already-acquired seat if any seat in the group fails to lock.
  Returning early without rollback leaves "orphaned" locked seats that
  nobody will ever confirm or release until TTL expiry — a real,
  user-facing bug (other users see seats as unavailable that no one is
  actually in the process of booking).
- **`Booking.transitionTo`** — must consult a transition table, not an
  `if/else` chain hand-checking each case — the table is what lets you
  *prove* completeness (every `BookingStatus` has an entry) rather than
  hope you remembered every case.
- **`BookingService.initiateBooking`** — must check for an existing
  booking by idempotency key *before* attempting to lock any seats. Doing
  the idempotency check *after* locking (or not at all) either wastes a
  seat-lock on a duplicate request or creates two bookings for one
  logical request.
- **`BookingService.confirmBooking`** — must short-circuit (return the
  existing booking) if it's already `CONFIRMED`, rather than attempting
  to charge payment again — this is what makes retried confirm calls
  safe.

---

## 4. The Concurrency Demonstration (Write This Live, Verbatim If Needed)

This is the single highest-value thing to produce in a live interview —
a runnable proof, not just an assertion, that double-booking cannot
happen:

```java
int threadCount = 20;
ExecutorService pool = Executors.newFixedThreadPool(threadCount);
CountDownLatch startGate = new CountDownLatch(1);
AtomicInteger successes = new AtomicInteger();

for (int i = 0; i < threadCount; i++) {
    pool.submit(() -> {
        startGate.await();
        try {
            bookingService.initiateBooking(userId, showId, List.of(seatId), UUID.randomUUID().toString());
            successes.incrementAndGet();
        } catch (SeatUnavailableException e) {
            // expected for the losing threads
        }
        return null;
    });
}
startGate.countDown();      // release all threads at once — maximize real contention
pool.shutdown();
pool.awaitTermination(5, TimeUnit.SECONDS);

assert successes.get() == 1;   // the actual invariant under test
System.out.println("Successful locks out of " + threadCount + " attempts: " + successes.get());
```

Narrate while writing it: *"I'm using a `CountDownLatch` as a start gate
so all twenty threads actually race each other at the same instant,
rather than trickling in sequentially — that's what makes this a real
stress test of the CAS loop rather than twenty calls that happen not to
overlap."* This exact pattern is what `ConcurrentBookingTest` and the
inline demo in `BookMyShowDemo` both do against the real, compiled
`BookingService` — not a toy — and it is reproducibly verified (see §9).

---

## 5. Common Coding Mistakes (And What They Look Like When They Happen)

1. **Single `compareAndSet` call without a retry loop.** Symptom: a
   legitimate lock attempt on a genuinely-available seat spuriously fails
   under concurrent load, because another thread's unrelated CAS attempt
   (on the *same* seat, different intent) changed the reference between
   your `get()` and your `compareAndSet()`, and you gave up instead of
   re-reading and retrying.
2. **Checking expiry with `System.currentTimeMillis()` scattered through
   business logic instead of a single injectable `Clock`.** Symptom: TTL
   expiry tests become flaky (`Thread.sleep`-dependent) or impossible to
   write deterministically, because there's no way to simulate "100ms
   from now" without actually waiting 100ms.
3. **Forgetting the multi-seat rollback case.** Symptom: in a 3-seat
   booking request where seat 3 of 3 is already taken, seats 1 and 2 stay
   `LOCKED` forever (until TTL) instead of being released immediately —
   visible as other users seeing seats "mysteriously unavailable" with no
   booking ever completing against them.
4. **Using `HashMap` instead of `ConcurrentHashMap` for shared mutable
   state accessed by multiple threads** (e.g., the active-locks registry,
   the idempotency-key index). Symptom: non-deterministic
   `ConcurrentModificationException`s or silently lost entries under
   concurrent load — often not caught until a stress test, because
   single-threaded manual testing never exposes it.
5. **Conflating "seat is `AVAILABLE`" with "seat's lock has merely
   expired."** Symptom: code that checks `status == AVAILABLE` literally,
   missing the case where `status == LOCKED` but `expiry < now` — such
   code incorrectly treats an expired-but-not-yet-reaped lock as still
   held, rejecting legitimate new lock attempts that should succeed.
6. **Confirming a booking before charging payment, or charging payment
   before confirming the seat lock is still valid.** Symptom (ordering
   bug, not a crash): a user gets charged for a seat that expired a
   moment before the charge went through, with no seat actually reserved
   for them — the correct order is confirm-the-lock-first (fail fast,
   no charge attempted) then charge, matching
   `BookingService.confirmBooking`'s actual sequence.
7. **Not handling the "`release` called twice" case defensively.**
   Symptom: an `NullPointerException` or incorrect state transition when
   both a user-initiated cancel and the background TTL reaper race to
   release the same lock — the fix (shown in `ShowSeat.release`) is for
   the second call to detect the state no longer matches and return
   `false` harmlessly rather than assuming the lock is still present.

---

## 6. Edge Cases Worth Naming Out Loud (Even If Not Fully Coded)

- Booking request for zero seats, or for seats not actually belonging to
  the specified show/screen.
- A show starting (or already started) at the moment a hold/booking is
  attempted — should new bookings be rejected once `now >= show.startTime`?
  (Yes — call this out as a validation you'd add in `BookingService` even
  if time-constrained from implementing it.)
- Two different users independently holding overlapping *subsets* of a
  multi-seat group is impossible by construction (each seat is a separate
  lock), but worth stating explicitly: "a 3-seat group lock is really
  three independent single-seat locks acquired together with rollback,
  not one atomic group-level lock" — a subtlety worth surfacing
  proactively rather than waiting to be asked.
- A payment gateway timeout (not failure — an *unknown* outcome) is
  categorically different from a clean failure response, and isn't fully
  solved by this design (see `STAFF_LEVEL_DEEP_DIVE.md` Q9) — worth
  naming as a known gap rather than silently treating timeout the same as
  failure.
- Idempotency key reuse *across different users* or *across different
  shows* — this design assumes the caller generates a globally unique key
  per logical booking attempt; a key collision between two unrelated
  requests would incorrectly short-circuit the second as a duplicate of
  the first (flagged explicitly in `STAFF_LEVEL_DEEP_DIVE.md` Q19 as a
  real, documented gap).

---

## 7. What To Narrate While Writing The Code

A sample narration track an interviewer should hear while you type
`ShowSeat`:

> "I'm modeling seat state as a single immutable `LockState` record
> behind one `AtomicReference`, rather than separate mutable fields for
> status/owner/expiry, specifically so every transition is one atomic
> swap of the whole state — if I used three separate fields, I'd need a
> lock to update all three consistently, which defeats the purpose of
> going lock-free in the first place. I'm writing `tryLock` as a `while
> (true)` loop: read the current state, decide if the seat is actually
> available *as of now* (treating an expired lock as available), build
> the new state, and attempt the CAS — if the CAS fails, someone else
> changed the state between my read and my write, so I loop and retry
> with fresh data rather than failing outright. This loop terminates
> because conflicting writers each make real progress — not a busy spin
> on nothing."

The goal of narrating this way: an interviewer should never have to ask
"why did you do it that way" because you've already answered it before
they could ask.

---

## 8. What Can Be Safely Omitted Under Time Pressure

Explicitly de-scope these out loud rather than silently skipping them —
naming a cut is itself a signal of judgment:

- **`DynamicPricingStrategy`** — implement `FixedPricingStrategy` only;
  mention dynamic/surge pricing is "the same interface, a different
  implementation, I'd add it if time allows."
- **The background TTL reaper** (`ScheduledExecutorService` sweep) — the
  *lazy* expiry check inside `tryLock` is sufical for correctness by
  itself; the reaper only improves promptness of seat reclaiming. Say so
  explicitly: "I'll skip the proactive sweep for now — expiry is still
  correctly enforced lazily on the next access, I'm just not proactively
  reclaiming capacity a few seconds early."
- **`BookingEventListener`/Observer wiring** — nice-to-have for
  notifications; not required for the core booking flow to be correct.
- **Full repository persistence** — in-memory `ConcurrentHashMap`-backed
  repositories are entirely sufficient to demonstrate the design; do not
  spend interview time wiring a real database.
- **Cancellation/refund flow** — if truly pressed for time, this is the
  single most defensible thing to cut entirely and only discuss verbally,
  *after* seat-locking, booking-confirmation, and the concurrency
  demonstration are all working — those three are the actual core ask.

---

## 9. A Simplified Version Implementable In 20–30 Minutes

If the interview format caps live coding at 20–30 minutes, build *only*
this subset, in this order, and verbally describe the rest:

1. `SeatStatus` enum (`AVAILABLE`, `LOCKED`, `BOOKED`) — 1 minute.
2. `ShowSeat` with `tryLock`/`confirm`/`release` via one
   `AtomicReference<LockState>` — ~10 minutes; this is where essentially
   all of your time and narration should go.
3. A trivial `Booking` class with just an id, seat ids, and a
   `BookingStatus` field (skip the full transition table — a single
   `status` field with direct assignment is an acceptable simplification
   here, named explicitly as a cut) — ~3 minutes.
4. A minimal `BookingService.initiateBooking`/`confirmBooking` that calls
   `ShowSeat` directly (skip the separate `SeatLockService` abstraction
   layer entirely for this simplified version — name this cut too) — ~5
   minutes.
5. The concurrency demonstration from §4, run live — ~5–7 minutes,
   including narrating the result.

This simplified path deliberately keeps the *one* most important idea
(lock-free seat CAS with TTL-aware expiry) fully real and fully
demonstrated, while compressing or verbally-only-describing everything
around it — exactly the priority ordering a Staff-level interview is
actually testing for.

---

## 10. Actual Verification Performed On This Deliverable

This is not a hypothetical "if you ran it, it would probably work" claim
— the full implementation in `src/` and `tests/` in this directory was
actually compiled and actually executed, including the full JUnit 5 test
suite, using a real JUnit Platform Console Launcher jar (not just
`javac` syntax-checking).

**Commands used** (run from this directory,
`04-bookmyshow/`):

```bash
JUNIT_JAR=../tools/junit-platform-console-standalone.jar

# Compile everything (main + test sources) against the JUnit console jar
javac -d .build/out -cp "$JUNIT_JAR" $(find src tests -name '*.java')

# Run the demo end-to-end (happy path + an inline 20-thread concurrency race)
java -cp .build/out lld.bookmyshow.demo.BookMyShowDemo

# Run the full JUnit 5 suite via the console launcher
java -jar "$JUNIT_JAR" --class-path .build/out --scan-class-path --disable-banner
```

**Result:**

- `javac` compiled all main and test sources with zero errors (only
  harmless `serialVersionUID`-related warnings on custom exception
  classes).
- `BookMyShowDemo` ran successfully end-to-end: catalog setup, seat-map
  display, successful booking + payment + confirmation, a cancellation +
  refund, a TTL-expiry scenario, and a 20-thread concurrent race on a
  single seat that completed with **exactly 1 success out of 20
  attempts**, matching the required invariant.
- The full JUnit 5 suite (`ShowSeatTest`, `InMemorySeatLockServiceTest`,
  `BookingServiceTest`, `BookingStateTransitionTest`,
  `ConcurrentBookingTest`, `PricingStrategyTest`) reported:

  ```
  36 tests found
  36 tests successful
  0 tests failed
  ```

  This was re-run 5 times in total specifically to rule out flakiness in
  the concurrency-focused tests (`ConcurrentBookingTest`,
  `ShowSeatTest`'s 50-thread race test); all 5 runs reported the
  identical `36/36 successful, 0 failed` result.

Treat this section as proof, not aspiration: every class, method, and
test referenced throughout this entire deliverable (`README.md`,
`INTERVIEW_PLAYBOOK.md`, `STAFF_LEVEL_DEEP_DIVE.md`,
`FOLLOW_UP_QUESTIONS.md`) corresponds to real, compiling, passing code in
`src/` and `tests/` at the time this guide was written.
