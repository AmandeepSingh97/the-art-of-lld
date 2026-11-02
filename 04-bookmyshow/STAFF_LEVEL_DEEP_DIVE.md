# Staff-Level Deep Dive — BookMyShow (Movie Ticket Booking System)

Problem-specific, reasoning-heavy questions a Staff/Principal-level panel
would plausibly raise against *this exact design*. Each answer reasons
from the actual code in `src/`, not generic platitudes.

---

### 1. Your seat state machine uses optimistic CAS. Walk me through what happens if a thread reads `AVAILABLE`, gets preempted by the OS for 50ms, and three other threads lock-and-confirm-and-cancel the seat in the meantime. Is there any way your loser thread incorrectly succeeds?

No — and this is the core correctness argument, worth stating precisely.
`tryLock`'s CAS compares against the *exact reference* (`current`) it read
before being preempted, not against "whatever `AVAILABLE` looked like." If
three other operations ran while this thread was asleep, `stateRef`'s
actual value is now a *different object* than `current` (even if, by
coincidence, its *logical* status is also `AVAILABLE` again after a
cancel). The preempted thread's `compareAndSet(current, next)` fails
because `current` is no longer the atomically-held reference — the ABA
problem does not bite here because we never recreate bit-identical
`LockState` objects and compare by value; every `tryLock`/`confirm`/
`release` allocates a *fresh* `LockState` object, so reference identity
is sufficient and no ABA mitigation (e.g., a version counter) is needed.
The preempted thread then loops, re-reads the current state, and
re-evaluates — if it's genuinely available now, it proceeds correctly
against the *current* snapshot; if not, it returns `false`. There is no
window where a stale read causes an incorrect success.

### 2. What happens if the process crashes between `paymentGateway.charge` succeeding and `seatLockService.confirmLock` running?

This is the most important real gap in the in-memory design, and it's
worth naming without being asked. In the current implementation, a crash
in that exact window leaves: the customer charged (payment succeeded),
the booking still `PAYMENT_PENDING` in memory — which, being in-memory,
is now *gone* on restart. The seat eventually expires back to `AVAILABLE`
via TTL, so no double-booking occurs, but the customer is charged with no
record of why. This is a **durability** gap, not a **concurrency** gap —
the concurrency invariant (one seat, one booking) still holds; what's
lost is the audit trail linking the charge to the booking. In a
persistent-store production version (§12 of the README), this becomes a
crash-recovery problem solved the standard way: write the `Booking` row
to durable storage *before* calling the payment gateway (status
`PAYMENT_PENDING`), so a recovery process can reconcile "charged but
status still pending" bookings against the payment gateway's own
transaction log after restart, and either complete the confirm or issue a
refund. The in-memory demo intentionally does not build this reconciler —
it's flagged as the honest limitation of choosing in-memory storage for
the exercise.

### 3. Your rollback-on-partial-failure approach for multi-seat locks assumes `release()` always succeeds when called on a seat you just acquired. What if it doesn't?

Good catch to examine: `release(lockId)` only fails (`returns false`) if
the current state is no longer `LOCKED` under that exact `lockId` — which,
for a seat *we* just successfully acquired moments earlier in the same
`lockSeats` call, could only happen if something else intervened. Could
that happen? Only the reaper could touch this seat in that tiny window
(no legitimate client holds our `lockId`), and the reaper only acts on
locks already past their expiry — a lock freshly acquired within the same
method call, microseconds old, cannot have expired yet (barring a TTL of
literally zero, which isn't a configuration this system accepts
meaningfully). So in practice `release` during rollback always succeeds.
If it somehow didn't (e.g., a pathological near-zero TTL), the rollback
loop still calls `release` on every acquired seat unconditionally and
ignores the boolean return — a seat whose `release` "fails" because it
already expired-and-was-reclaimed is, definitionally, already back to
`AVAILABLE` by the very definition of expiry, so the net outcome (seat
ends up `AVAILABLE`) is identical either way. The rollback is safe even in
that edge case, just not for the reason naive code-reading suggests.

### 4. Why doesn't `confirmLock`'s partial-failure rollback risk leaving some seats BOOKED permanently if the "roll back to AVAILABLE" step itself races with something?

`confirmLock`'s rollback calls `ShowSeat.cancelBooking()`, which CASes
`BOOKED -> AVAILABLE` unconditionally (no lock-id check, since a booked
seat by definition has no active lock-id anymore). The only entity that
could concurrently touch a seat we just confirmed to `BOOKED` is another
call to `cancelBooking()` on the *same* seat — but nothing else has a
reason to call `cancelBooking()` on this seat right now, because no other
booking holds it (we just confirmed it under our lock, exclusively). So
there's no realistic racer. If there somehow were two concurrent
`confirmLock` rollbacks hitting the same already-`BOOKED` seat (shouldn't
happen given one lock per seat, but suppose it did via a bug elsewhere),
the second `cancelBooking()` call would simply return `false` (current
status already `AVAILABLE`, not `BOOKED`) — idempotent, not harmful.

### 5. Your idempotency key is a client-supplied string. What stops a malicious or buggy client from reusing someone else's idempotency key to read/hijack their booking?

It doesn't, in the current code — `BookingRepository.findByIdempotencyKey`
has no notion of *who* is allowed to use a given key; it's purely a
dedupe mechanism for a *single, trusted* client's own retries. This is a
legitimate gap to flag: a production API would scope the idempotency key
to the authenticated user (e.g., key the dedupe index by
`(userId, idempotencyKey)` rather than `idempotencyKey` alone), so one
user's key collision can never return another user's booking. The
in-memory design's `BookingRepository` would need exactly one change —
the secondary index key becomes a composite — to close this; it's not
built into the exercise because there's no `userId`-scoped auth layer in
this LLD's scope to begin with (§1, out-of-scope), but it's the right
thing to flag as soon as auth enters the picture.

### 6. You chose to compute the booking amount once at `initiateBooking` time and never recompute it. What if `DynamicPricingStrategy` is in effect and the show sells out between hold and confirm — doesn't that mean you're charging a stale price?

Yes, by design, and this is a defensible product decision, not an
oversight: once a user has held seats at a quoted price, re-pricing them
upward before they've even finished paying would be a poor (and in many
jurisdictions, legally questionable) user experience — "the price changed
while I was paying" is a classic dark pattern complaint. The design
intentionally locks in price at hold time. The trade-off: if demand spikes
in the few minutes of someone's checkout window, the platform "loses" the
marginal surge revenue on that specific booking. This is the correct
default; if a business genuinely wanted hold-time-of-payment pricing
instead, the fix is one line (recompute `amount` in `confirmBooking`
rather than reading the value stored on `Booking`), explicitly a product
decision to flip, not a technical limitation.

### 7. Walk me through exactly what the `sorted seat-id order` in `lockSeats` buys you, concretely — with CAS, isn't locking order irrelevant since nothing blocks?

Correct that it isn't preventing *deadlock* (no blocking primitive exists
here to deadlock on). What sorted order buys is **reduced wasted work and
predictable conflict resolution** under the specific pathological case of
two overlapping group bookings racing in opposite relative order. Without
sorting: request X wants {A1, A2} and processes A1 then A2; request Y
wants {A2, A1} and processes A2 then A1. It's entirely possible for X to
acquire A1, Y to acquire A2, and then both fail on their second seat and
both roll back — net result: *nobody* got either seat on this pass, despite
there being no actual permanent conflict (each individual seat only ever
had one real claimant at the CAS level, but the *group* semantics
produced a mutual blockout). With a canonical sort order, both X and Y
attempt A1 first — exactly one of them wins A1 outright, and that winner
proceeds cleanly to A2 while the loser fails fast on A1 without ever
touching A2 at all. This doesn't eliminate contention, but it eliminates
this specific "both groups fail because they crossed streams" outcome,
which is strictly better for overall system throughput under multi-seat
contention.

### 8. Your `activeLocks` map is keyed by `lockId`, which is a random UUID. What's the actual cost of the reaper's full sweep, and does it scale?

The reaper's `sweepExpiredLocks` iterates every entry in `activeLocks` —
which has size proportional to *concurrently mid-checkout* bookings
(seats currently held but not yet confirmed/released/expired), not total
seats or total historical bookings. For a real high-traffic system, this
number could reasonably be in the thousands during a peak on-sale moment
(e.g., a blockbuster's tickets going live), and a full-map iteration every
sweep interval is O(active holds) per sweep — acceptable but not free at
very large scale. A more scalable structure would be a **min-heap /
delay-queue ordered by expiry time**, so the reaper only ever looks at the
*front* of the queue (soonest-expiring locks) and stops as soon as it
finds one not yet expired, rather than scanning the whole set every tick —
turning an O(n) sweep into an O(k log n) operation where k is the number
of expirations actually due this tick. This is flagged, not implemented,
because at LLD/demo scale (realistically dozens to low hundreds of
concurrent holds) the simpler structure is clearer to read and reason
about, and premature optimization here would obscure the core concurrency
story this exercise is about.

### 10M users, system under real load: where does this design hit its first wall?

Not seat-level CAS contention — that scales with the number of genuinely
*hot* seats at any instant, which is bounded regardless of total user
count (you can't have more simultaneous claimants on one seat than there
are humans clicking in the same second, which is a small number even for
a very popular show). The first real wall is **single-instance
throughput**: this entire design, as built, runs in one JVM with
in-memory state. 10M users browsing/booking cannot be served by one
process's heap and CPU. The very next design decision — before anything
else — is horizontal scaling, which immediately raises the distributed-
coordination question (§14/README): once there's more than one instance,
this JVM's `ConcurrentHashMap`/`AtomicReference` state is no longer a
single source of truth, and the seat-concurrency guarantee has to move to
a shared substrate (Redis atomic ops, or a DB row with optimistic
locking). That migration is a close analog of this code (same CAS shape,
different physical location for the compare-and-swap), which is precisely
why this design was built the way it was — to make that migration
mechanical rather than a rewrite.

### 9. If I handed you this exact codebase and said "add a requirement: users can hold seats for FREE up to 2 at a time per show, but a 3rd seat requires an active paid subscription" — what in your current design has to change, and what doesn't?

`ShowSeat`/`InMemorySeatLockService` — the entire concurrency core —
doesn't change at all; this is purely a *business-rule* addition, not a
concurrency concern. The right seam is `BookingService.initiateBooking`:
add a precondition check (count the user's currently-held/booked seats
for this show, or globally, depending on the exact rule's scope) *before*
calling `seatLockService.lockSeats`, throwing a new, clearly-named
exception (e.g., `SubscriptionRequiredException`) if the rule is
violated. This is a strong example of why keeping `BookingService` as a
thin *orchestrator* over well-isolated pieces (seat locking is one
concern, payment is another, now "entitlement checking" is a third) pays
off: a business-rule change like this touches exactly one method, adds
one new exception type, and requires zero changes to the actual
concurrency-critical code. If this interviewer-posed hypothetical instead
required changing *how seats are locked* (e.g., "temporarily reserve up
to 2 seats with no TTL at all, unlike paid holds"), that would be a
bigger change — a second `SeatLockService` variant or a lock "kind"
parameter — worth contrasting explicitly to show you can tell the
difference between a cheap extension and an expensive one.

### 10. Your `Booking.transitionTo` throws on an invalid move but `tryTransitionTo` silently returns `false`. Isn't having two methods for the same underlying check a design smell — duplicated logic?

Fair challenge, but it's not duplicated logic — both methods call the
exact same CAS loop against the exact same `TRANSITIONS` table; the *only*
difference is what happens on failure (throw vs. return `false`), and
that's a deliberate, justified fork based on *caller context*, not
incidental duplication. `transitionTo` is used on paths where an invalid
transition is a genuine bug or an exceptional, caller-facing situation
that should halt and surface loudly (e.g., a user-facing API call hitting
an impossible state transition is worth a stack trace). `tryTransitionTo`
is used exactly once, by the TTL-expiry callback running on a background
reaper thread, where "this booking already raced ahead to CONFIRMED" is
an entirely expected, non-exceptional outcome of normal concurrent
operation — throwing there would mean either swallowing exceptions
silently in the callback (worse) or crashing the reaper thread (much
worse — it would stop sweeping *all* future expirations). Two thin methods
sharing one real implementation, forking only on error-handling policy,
is the right level of DRY here — collapsing them into one method would
force every caller to either always risk an exception or always silently
swallow failures, both wrong for at least one of the two actual callers.

### 11. What's the worst-case behavior if `PricingStrategy.priceFor` throws an exception (e.g., a bug in `DynamicPricingStrategy`'s date math)?

Currently, an uncaught exception from `priceFor` during
`BookingService.computeAmount` (called from `initiateBooking`, *after*
`seatLockService.lockSeats` has already succeeded) would propagate up and
abort `initiateBooking` — but the seats are already locked at that point
and the method never reaches `bookingRepository.save`, so there is no
`Booking` row tracking that hold. The seat(s) would sit `LOCKED` with no
corresponding booking until the TTL reaper eventually reclaims them (or
the lazy-reclaim path does, on the next `tryLock` against that seat) —
correctness is preserved (no double-booking, no permanent seat loss) but
the user experiences an opaque failure and the seats are unusable by
anyone until TTL expiry, a worse user experience than necessary. The
concrete fix: wrap the amount computation in a try/catch inside
`initiateBooking` that releases the just-acquired lock before rethrowing,
turning a "pricing bug leaves seats stuck for 5 minutes" failure mode into
"pricing bug fails fast and immediately frees the seats." This is a real,
fixable gap worth naming proactively if asked to harden the code further.

### 12. You said CAS avoids deadlock structurally. Can two *different* bookings still livelock each other, each perpetually failing the other's attempts?

No, and it's worth being precise about why. A "livelock" would require two
threads to keep *changing* a shared resource's state in a way that keeps
defeating each other's progress indefinitely. Here, `tryLock`'s loser path
is a single `return false` — there is no retry built into losing a
contested seat; the caller (`InMemorySeatLockService`) surfaces
`SeatUnavailableException` immediately rather than spinning. The only
retry loop that exists is the CAS *compare-and-retry* loop *within one
caller's own attempt*, which terminates the instant either (a) this
caller's CAS succeeds, or (b) this caller observes the seat is no longer
available (someone else's CAS already won) — both are terminal,
non-retrying outcomes for that call. So there's no scenario where two
threads perpetually "fight" over the same seat in an infinite loop; the
system fails fast on contention by design, which is itself a deliberate
choice over, say, an auto-retry-with-backoff policy that *could*
theoretically livelock under pathological timing (though even that would
be bounded in practice by exponential backoff).

### 13. If I challenged "your whole architecture is built around a *single* `Show`'s inventory being independent of every other `Show`'s inventory" — is that actually true, and what would break it?

It's true as built, and it's a load-bearing assumption worth stating
explicitly: `SeatInventoryRepository`'s outer map is keyed by `showId`,
and nothing in `ShowSeat`/`InMemorySeatLockService`/`BookingService` ever
reasons across two different shows simultaneously for a single operation.
This is correct for this domain — a seat for a 6 PM showing and the
"same" seat for a 9 PM showing truly are independent resources that
happen to share a physical chair. It would break only if a *new*
cross-show requirement were introduced — e.g., "a user can't hold more
than 10 seats total across *all* shows at once, to prevent scalping bots"
— which would require a *second*, genuinely cross-cutting piece of state
(e.g., a per-user active-hold counter) layered *alongside* the existing
per-show inventory, not a redesign of it. Worth naming as "this
assumption is correct for the stated requirements and would need an
additional, separate mechanism — not a rework of this one — if a
cross-show constraint were added."

### 14. Your `BookingRepository.save` does a `putIfAbsent` for the idempotency index but an unconditional `put` for the main store. Is there a race between those two operations?

Good, specific question. `save` first writes the main `store.put` (via the
parent `InMemoryRepository.save`), *then* does
`byIdempotencyKey.putIfAbsent`. Consider two concurrent `save` calls for
*the same* `Booking` object reference (which only happens if
`initiateBooking` is called twice for the same already-existing booking,
itself only possible if a caller bypassed the idempotency check in
`initiateBooking` — which it never does, since that check runs *before*
`save` is ever reached). So in practice `save` is called at most once per
distinct booking id with a distinct idempotency key, making this a
theoretical rather than live concern. If it *were* possible for two
different `Booking` objects to race to `save()` with the same
idempotency key (a bug elsewhere), `putIfAbsent` ensures only the first
one's id wins the secondary index — the second `Booking` would still be
written to the main store (reachable by its own id) but invisible via
idempotency lookup, which is a data-consistency wrinkle worth flagging:
the *correct* production fix would make the entire `initiateBooking`
sequence (check-then-insert) atomic via a single
`bookingRepository.computeIfAbsent`-style operation keyed by idempotency
key, rather than today's separate "check, then later save" steps separated
by the seat-locking work in between — today's design accepts a narrow
theoretical TOCTOU window here in exchange for simplicity, since the
realistic trigger (the exact same idempotency key submitted twice
*truly concurrently*, both passing the initial `findByIdempotencyKey`
miss) requires a client bug (reusing a key concurrently rather than
sequentially) that is out of scope but worth naming.

### 15. Why is `Payment` a separate entity from `Booking` at all, rather than a couple of fields on `Booking`?

Two reasons, both real: (1) a `Booking` can have **zero** payments (failed
before charging), and conceivably more than one over its lifetime in a
production extension (a failed charge followed by a successful retry
under a *new* idempotency key producing a second `Payment` row linked to
the same booking) — modelling this as a separate entity with its own
identity and status lifecycle is more honest than cramming an optional,
possibly-repeating concept onto `Booking`'s fields. (2) `Payment` has
genuinely distinct lifecycle concerns (refund tracking, a payment
reference id from the external gateway) that don't belong conceptually on
the booking aggregate — keeping them separate also means a future
"payment history/ledger" feature has a natural home (`PaymentRepository`)
without needing to retrofit `Booking` into an array-of-payments shape
later.

### 16. Is `SeatLock` actually necessary as a class, or could `lockSeats` just return the `lockId` string?

It's worth defending this one honestly: a bare `String lockId` would work
functionally — every method that takes a `SeatLock` elsewhere could
instead take a `lockId` plus look up details separately. `SeatLock` earns
its existence by being the one place `expiresAt` is communicated back to
the caller *without* a second round-trip — `BookingService` needs
`lock.getLockId()` to store on the `Booking`, but a well-behaved caller
(or a future API response) also plausibly wants to show the user "you
have 4:32 left to complete payment," which needs `expiresAt`. Bundling
`lockId` + `showId` + `seatIds` + `ownerId` + `expiresAt` into one
immutable value object is a small, justified convenience, not
over-engineering — it's a 5-line value object, not a new abstraction
layer.

### 17. What's your answer if asked to add idempotent *cancellation* (currently `cancelBooking` isn't idempotency-keyed)?

Currently `cancelBooking(bookingId)` isn't idempotent via a key because
it doesn't need an *external* idempotency key to be safe against retries
— it's naturally idempotent-safe-to-retry in a different sense:
`Booking.transitionTo(CANCELLED)` only succeeds once (the legal source
state is `CONFIRMED`, and after the first successful cancellation the
booking is `CANCELLED`, a terminal state with no outgoing transitions), so
a retried `cancelBooking` call on an already-cancelled booking throws
`InvalidBookingStateException` — safe (no double-refund can occur,
because the state check happens before `paymentGateway.refund`), but not
*silent* the way `confirmBooking`'s idempotent re-entry is (which returns
the same object rather than throwing). Whether that's the right UX
depends on the caller: if a client might legitimately retry a cancel
request after a timeout, `cancelBooking` should probably catch "already
cancelled" and return the existing cancelled booking rather than
propagate the exception — a one-line change
(`if (booking.getStatus() == CANCELLED) return booking;` added at the top,
mirroring `confirmBooking`'s pattern) that wasn't built into the base
design because the problem statement didn't call for cancellation retry
semantics explicitly, and volunteering it unprompted is a good staff-level
signal.

### 18. Your tests use an `AdjustableClock` to avoid `Thread.sleep`-based flakiness, except one (`backgroundReaperActivelyReleasesExpiredLocksAndFiresListener`) which does wait on real time. Isn't that inconsistent, and is it a flaky test risk?

It's a deliberate, narrow exception, not an inconsistency, and it's
flagged as such directly in that test's own comments. The *lazy* expiry
path (checked by every other expiry-related test) is purely a function of
the logical clock passed into `ShowSeat`/`InMemorySeatLockService`, so
`AdjustableClock` fully controls it with zero wall-clock dependence. The
*active reaper*, however, is a real `ScheduledExecutorService` running on
a real daemon thread on a fixed **wall-clock** interval (`scheduleAtFixedRate`
with real milliseconds) — that's a conscious design choice (the reaper
shouldn't be driven by the same logical clock a test can freeze, since in
production there's no "logical clock" to control at all, only real time).
To test that specific real-time behavior, the test has to either wait on
real wall-clock time (what it does, with a generous 2-second timeout
against a 50ms sweep interval — effectively 40x margin) or replace the
reaper's `ScheduledExecutorService` with an injectable, test-controlled
scheduler — a bigger refactor for marginal benefit at this scale. The
chosen trade-off (one test with a generous real-time bound, clearly
commented) is reasonable and has been run repeatedly with zero flakes
during this deliverable's verification (see `IMPLEMENTATION_GUIDE.md`);
if this *were* observed to flake under CI load, the fix would be
dependency-injecting the scheduler rather than increasing the timeout
further.

### 19. If you had to ship a simplified version of this in 2 weeks, what would you cut, and what would you absolutely refuse to cut?

Cut: dynamic/surge pricing (ship `FixedPricingStrategy` only — the
interface stays, just one implementation), cancellation refund-percentage
nuance (ship full-refund-only), search filters beyond movie+city+date
(language/format filters later), and the `BookingEventListener`
notification hook (ship with zero listeners registered — the seam costs
nothing to leave in place unused). Absolutely refuse to cut: the
`ShowSeat` CAS state machine and multi-seat rollback logic (this *is* the
product — a ticketing system that double-sells seats is not a smaller
version of the product, it's a broken one), idempotent `confirmBooking`
(payment double-charge bugs are the single most expensive and
reputation-damaging class of bug this kind of system can ship with), and
TTL-based seat release (without it, a single abandoned checkout
permanently removes a seat from sale — silently shrinking inventory over
time with no recovery path is unacceptable for a 2-week MVP just as much
as for a mature product).

### 20. Your design treats `BookingService` as a single class orchestrating lock + pay + confirm. At what point does this become a god class, and how would you know?

The honest signal to watch for is **responsibility creep disguised as
orchestration**: `BookingService` currently only *calls* `SeatLockService`,
`PaymentGateway`, and `PricingStrategy` — it doesn't implement seat-locking
logic, payment logic, or pricing logic itself; each of those lives in its
own class with its own tests. It becomes a god class the moment it starts
*containing* logic that belongs in one of those seams instead of just
calling it — e.g., if "retry the payment gateway 3 times with backoff"
logic got written inline inside `confirmBooking` rather than inside
`PaymentGateway`/a `RetryingPaymentGateway` decorator, or if
entitlement/subscription rules (question 9, above) got written as a
10-line inline check rather than extracted once they grew past a single
`if`. The concrete warning sign in review would be `BookingService`
growing multiple *unrelated* private helper methods that each encapsulate
a full sub-algorithm (not just a one-line delegation) — at that point,
each such helper is a strong candidate to be pulled out into its own
class with its own interface and tests, exactly the same move already
applied to seat-locking and payment.

### 21. Why `EnumMap`/`EnumSet` for the booking transition table instead of a plain `HashMap<BookingStatus, Set<BookingStatus>>`?

Purely a (minor, but free) performance and intent-signalling choice:
`EnumMap`/`EnumSet` are backed by arrays indexed by the enum's ordinal,
giving O(1) *with a smaller constant and no hashing* lookup/membership
test compared to a general `HashMap`/`HashSet`, and they communicate to
any reader "this map/set's keyspace is exactly this closed, small enum" —
a `HashMap<BookingStatus, Set<BookingStatus>>` would work identically in
terms of correctness but loses that self-documentation and the (tiny but
real) performance edge. For a transition table checked on every single
state change, including under the TTL-expiry callback racing a user
request, this is exactly the kind of place where the more specialized
collection is worth reaching for.

### 22. Is there any scenario in which `getEffectiveStatus` (used for display in `CatalogService.getSeatMap`) could show a seat as `AVAILABLE` to one user while it's actually about to be confirmed `BOOKED` by another — and is that a problem?

Yes, and it's an acceptable, well-understood **read-your-writes vs.
eventual display consistency** trade-off, not a bug. `getEffectiveStatus`
is a point-in-time, best-effort read used purely for *display* (the seat
map a user sees while browsing) — it is explicitly not, and should never
be mistaken for, the authority used to grant a lock. The authority is
always the live `compareAndSet` inside `tryLock` at the moment the user
actually attempts to select/hold that seat. So the realistic sequence is:
user A sees "AVAILABLE" on their seat map (accurate at that instant), user
B concurrently locks it a moment later, user A then clicks "select" and
calls `initiateBooking`, which correctly fails with
`SeatUnavailableException` because the *authoritative* check happens at
that moment, not at display time. This is exactly how every real
ticketing UI behaves (you can see a seat as green and still lose the race
when you click it) and is the right trade-off — making the seat map
itself strongly consistent would require a far more expensive mechanism
(e.g., server-push invalidation on every seat-map viewer, for every seat
state change) for a benefit (a marginally fresher *display*, not a
materially different *outcome*) that doesn't justify the cost.
