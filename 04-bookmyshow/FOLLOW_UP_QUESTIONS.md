# Follow-Up Questions — BookMyShow (Movie Ticket Booking System)

30 realistic interviewer follow-ups, categorized, each with a full answer
grounded in the actual design in `src/` and `README.md`. No question is
repeated across categories even where topics brush against each other.

---

## Object-Oriented Design

**1. Why is `ShowSeat` a separate class from `Seat` instead of adding a `status` field directly to `Seat`?**
Because `Seat` describes a *physical* chair in a screen (row, number,
category) which is show-independent and never changes, while
availability is inherently *per-show* — the same physical seat is
available for a 6 PM show and booked for a 9 PM show simultaneously. If
`status` lived on `Seat`, one physical seat could only ever have one
status at a time across *all* shows, which is simply wrong. `ShowSeat`
models the actual relationship correctly: one `Seat` → many `ShowSeat`
records, one per show it's scheduled into.

**2. Why does `Theatre` own `Screen` and `Screen` own `Seat` via composition (internal mutable lists with `addX` methods) instead of making them fully immutable at construction?**
Theatre/screen layout is naturally built incrementally during setup (a
theatre is created, then screens are added one at a time; a screen is
created, then its seat grid is added row by row) — forcing a single
giant constructor call with the complete seat list would make setup code
far less readable than it is in `BookMyShowDemo`/`TestFixtures`. The
trade-off is explicit: these collections are mutable *during setup* but
exposed externally only via `Collections.unmodifiableList`/`List.copyOf`
accessors, so no caller outside the owning class can mutate the layout
after the fact — "constructible incrementally, immutable to the outside
world" rather than "immutable always."

**3. `Booking` exposes `getSeatIds()` returning `List<String>` rather than a richer `List<Seat>`. Is that a layering smell?**
It's a deliberate choice to keep `Booking` decoupled from the catalog
model. `Booking` only needs seat *identity* to know what it reserved —
resolving an id to a full `Seat`/`ShowSeat` object is the job of whoever
needs that detail (`BookingService` does exactly this via
`seatInventoryRepository.findShowSeat` when it needs category/pricing
info). If `Booking` held `List<Seat>` directly, it would create a
bidirectional coupling between the booking aggregate and the catalog
object graph that isn't otherwise needed, and would complicate
serialization (a `Booking` DTO sent over a wire shouldn't need to embed
full seat objects).

**4. Why does `Show` store `Map<SeatCategory, BigDecimal>` for base prices instead of a `List<CategoryPrice>` or similar?**
A map keyed by the finite `SeatCategory` enum is the natural fit — lookup
by category is the only access pattern needed (`basePriceFor(category)`),
and a `Map<SeatCategory, BigDecimal>` makes "every category must have an
exactly-one price" easy to validate at construction and query in O(1),
versus a `List` which would need a linear scan or an awkward invariant
("no duplicate categories") to enforce manually.

---

## SOLID Principles

**5. Where does this design most clearly demonstrate the Single Responsibility Principle?**
`SeatLockService` only concerns itself with seat concurrency (hold/
confirm/release/expire); `PaymentGateway` only concerns itself with
charging/refunding; `PricingStrategy` only concerns itself with computing
a price; `BookingService` concerns itself only with *orchestrating* those
three in the right order and managing the `Booking` aggregate's
lifecycle — it contains zero seat-locking logic, zero payment-processing
logic, and zero pricing-math logic of its own. Each class has exactly one
reason to change: a new locking algorithm only touches
`SeatLockService`/its implementations; a new payment provider only
touches a new `PaymentGateway` implementation; a new pricing model only
touches a new `PricingStrategy` implementation.

**6. How does the Open/Closed Principle show up in the pricing design specifically?**
Adding `DynamicPricingStrategy` required zero changes to
`PricingStrategy` (the interface), `BookingService`, or `CatalogService`
— both already depend on the `PricingStrategy` abstraction, not a
concrete class. The system is open to new pricing behavior (new classes
implementing the interface) but closed to modification of existing,
already-tested orchestration code. Contrast this with the rejected
alternative (README §7) of a `switch` on a `PricingMode` enum inside
`BookingService`, which would have required editing and re-testing
`BookingService` itself for every new pricing model.

**7. Where would Liskov Substitution actually matter in this codebase, and is it upheld?**
It matters wherever a caller depends on an interface and must behave
correctly regardless of which implementation is plugged in:
`BookingService` must work correctly whether given `FixedPricingStrategy`
or `DynamicPricingStrategy` (both honor the same contract — "a
non-negative price for this category on this show at this instant," and
neither throws for valid inputs nor returns a price that violates that
contract), and must work correctly whether given `MockPaymentGateway`
with a passing or failing predicate (both honor "either both `success`
and a non-null `referenceId`, or `!success` and a non-null
`failureReason`" — `BookingService` never needs implementation-specific
knowledge of *which* `PaymentGateway` it's talking to). Both are upheld
because neither implementation narrows its preconditions or widens its
guaranteed postconditions relative to the interface contract.

**8. Does `Repository<ID, T>` violate Interface Segregation by bundling `save`/`findById`/`findAll` into one interface — should write and read be separate interfaces?**
At this interface's actual size (3 methods, all genuinely needed by every
implementor) splitting it into `ReadRepository`/`WriteRepository` would be
premature segregation — ISP exists to prevent forcing implementors to
depend on methods they don't need or can't sensibly implement, and every
repository in this system (`CityRepository` through `PaymentRepository`)
genuinely needs all three operations. ISP would become a real concern if,
say, a read-only reporting repository were added that had no business
exposing `save` at all — at that point splitting the interface would be
justified by an actual caller's need, not speculatively in advance.

**9. How does Dependency Inversion show up between `BookingService` and the things it depends on?**
`BookingService`'s constructor takes `SeatLockService`, `PaymentGateway`,
and `PricingStrategy` — all interfaces — never a concrete
`InMemorySeatLockService`/`MockPaymentGateway`/`FixedPricingStrategy`
type. The high-level orchestration module (`BookingService`) depends on
abstractions, and the low-level modules (the concrete implementations)
also depend on those same abstractions (by implementing them) — neither
depends on the other's concrete type. This is what makes
`BookingServiceTest` able to swap in a failure-injecting
`MockPaymentGateway` without touching `BookingService` at all.

---

## Design Patterns

**10. Could the Strategy pattern used for pricing also be applied to refund calculation, and would you do it the same way?**
Yes — a `RefundPolicy` interface (`BigDecimal refundAmountFor(Booking, Instant cancelledAt)`)
would be the natural extension if refund rules needed to vary (e.g., full
refund more than 24h before showtime, 50% within 24h, none within 1h). It
isn't built into this exercise because the clarifying-questions section
(§3, Q5) established a simpler "full refund always" assumption as
sufficient for this scope — but the seam is a one-interface addition away
precisely because `BookingService.cancelBooking` already isolates the
refund-amount decision to a single expression
(`payment.getAmount()`) that a strategy call could replace without
touching anything else in the method.

**11. You rejected the Command pattern for booking operations. If the interviewer specifically asked for an "undo" feature on recent bookings, would that change your answer?**
Possibly, but even then, Command isn't the first tool reached for: "undo a
recent booking" within the existing lifecycle is just
`cancelBooking(bookingId)` — already fully supported, since `CONFIRMED ->
CANCELLED` is a legal transition. True Command-pattern value would appear
if undo needed to be *generic across many different kinds of operations*
(undo a cancellation, undo a price override, undo an admin action) with a
shared undo/redo stack and history — a materially bigger ask than "let a
user cancel their own booking," which this design already handles via a
plain method call, not a reified command object.

**12. If you added a `NotificationService` that sends real emails/SMS, would you implement it as another `BookingEventListener`, or give `BookingService` a direct dependency on it?**
As another `BookingEventListener` implementation, registered via
`BookingService.addListener` — exactly the extension point the Observer
pattern exists for. Giving `BookingService` a direct field/dependency on
a concrete `NotificationService` would re-introduce the coupling the
listener interface was specifically added to avoid, and would mean every
new notification channel (push, Slack-for-ops-alerts, analytics) requires
editing `BookingService`'s constructor signature again.

**13. Why is `PricingStrategy` a `@FunctionalInterface` (one abstract method) rather than a richer interface with multiple methods (e.g., separate methods for base price vs. surge)?**
Because callers only ever need one thing from a pricing strategy — "the
price for this seat category on this show right now" — and a single
method keeps the contract minimal and lets simple strategies (like
`FixedPricingStrategy`) be written as a one-line lambda in principle
(though implemented as a named class here for discoverability and
javadoc). Splitting "base price" and "surge" into separate interface
methods would leak `DynamicPricingStrategy`'s internal decomposition (it
happens to compute occupancy-surge and proximity-surge separately) into
the public contract every implementation must honor, which
`FixedPricingStrategy` has no meaningful way to implement (it has no
surge concept at all) — forcing an awkward "return 1.0" stub.

---

## Data Structures

**14. Why `List<String>` for `Booking.seatIds` rather than `Set<String>`, given seats within one booking should never repeat?**
`List` preserves the caller's original ordering (useful for display:
"you booked A1, A2, A3" in the order selected), and duplicate seat ids in
one booking request are naturally rejected anyway — not by a `Set`'s
dedup behavior, but by `SeatLockService.lockSeats`'s per-seat
`tryLock`, which would simply fail the *second* `tryLock` attempt on a
duplicate id within the same group (the first call already moved it to
`LOCKED`, so a second attempt on the identical id in the same list fails
the CAS, correctly rejecting the nonsensical "book the same seat twice in
one request" case as a `SeatUnavailableException` without needing
explicit duplicate-detection code.

**15. Your `EnumMap`/`EnumSet`-based transition table is a `Map<BookingStatus, EnumSet<BookingStatus>>` built once as a `static final`. Is there any thread-safety concern with multiple threads reading it concurrently?**
No — `TRANSITIONS` is built once in a static initializer and never
mutated afterward; concurrent *reads* of an unmodified object (no writes
racing reads) are inherently safe under the Java Memory Model as long as
safe publication is guaranteed, which `static final` fields guarantee
(the JLS guarantees a class's static initializers complete, with their
effects visible to all threads, before any thread can observe the class
as initialized/use any of its static state). This is a good example of
"immutability is a free thread-safety win" distinct from the CAS-based
mutability-management used elsewhere in this codebase.

**16. Why does `SeatInventoryRepository` use nested maps (`Map<showId, Map<seatId, ShowSeat>>`) instead of a single flat map keyed by a composite `(showId, seatId)` key object?**
Both would work correctly; nested maps were chosen because
`findAllForShow(showId)` — a genuinely common access pattern (building a
seat map for display, or computing occupancy for `DynamicPricingStrategy`)
— is O(1) map lookup plus O(k) copy of that show's seats with nested
maps, versus requiring a full scan-and-filter over every seat of every
show with a flat map (unless a secondary index were added, which is
exactly what the nested structure already *is*, just without needing a
separate index to maintain). A composite-key flat map would need an
additional index structure to support "all seats for show X" efficiently
— the nested map *is* that index, for free.

---

## Java Internals

**17. Explain precisely what `AtomicReference.compareAndSet` does at the JVM/hardware level, and why it's safe without any `synchronized` block.**
`compareAndSet(expected, new)` is backed by an intrinsic that compiles
down to a hardware atomic instruction — `LOCK CMPXCHG` on x86-64, `LDXR`/
`STXR` (load-exclusive/store-exclusive) on ARM — that atomically checks
whether the memory location currently holds `expected` and, if so,
replaces it with `new`, all as one indivisible operation the CPU
guarantees no other core can observe a partial view of. There's no
`synchronized` block because there's no critical *section* to protect —
the entire read-compare-write happens as a single hardware-guaranteed
atomic step, rather than a multi-step Java-level operation needing a
monitor to make atomic.

**18. What does the Java Memory Model guarantee about visibility when one thread's `compareAndSet` succeeds and another thread later calls `get()` on the same `AtomicReference`?**
A successful `compareAndSet` establishes a happens-before relationship
with any subsequent `get()` that observes the value it wrote (this is
part of the formal guarantee `java.util.concurrent.atomic` classes
provide, same volatile-like semantics as a `volatile` field write/read
pair). Concretely: once thread B's `ShowSeat.getEffectiveStatus` call
observes `BOOKED` (written by thread A's successful `confirm` CAS), B is
also guaranteed to see every other value A had written before that CAS —
not just the `LockState` reference itself, but anything A did *before*
the CAS in program order. This is what makes it safe to reason about
`ShowSeat` without additional synchronization: "if you can see the new
state, you can see everything that led to it."

**19. Why does `Booking`'s transition table use `EnumSet.noneOf(BookingStatus.class)` for terminal states rather than just mapping them to `null`?**
Using an empty `EnumSet` rather than `null` means `TRANSITIONS.get(current)`
never itself needs a null-check distinguishing "no entry for this state"
(a genuine bug — every `BookingStatus` value must have an entry) from
"this state has no legal outgoing transitions" (an intentional terminal
state) — both cases are handled uniformly by `allowed == null ||
!allowed.contains(target)` in `transitionTo`, but giving terminal states a
real (empty) set rather than a missing map entry makes the map's
completeness independently verifiable/enumerable (e.g., an assertion that
`TRANSITIONS.keySet()` equals all `BookingStatus` values) in a way `null`
entries would obscure.

---

## Multithreading

**20. If you replaced every `AtomicReference`-based CAS loop in this codebase with a single global `synchronized` method guarding all seat operations across the entire system, would it still be correct?**
Yes, correctness would be preserved (a single global lock trivially
serializes all operations and so cannot allow two threads to double-book
a seat), but it would destroy the scalability property this design
specifically optimizes for: every seat lock/confirm/release across every
show and every seat in the entire system would contend for the *same*
one lock, meaning unrelated bookings on unrelated shows/seats would block
each other — turning what should be embarrassingly parallel work (seat A1
on Show 1 and seat B5 on Show 2 have nothing to do with each other) into
fully serialized work. This is the concrete, quantifiable cost of
"correct but needlessly coarse-grained" locking, and exactly why
per-seat (not per-show, not global) granularity was chosen.

**21. What's the actual difference in behavior between `ShowSeat.release` being called twice in a row with the same `lockId` — is the second call a bug?**
Not a bug — it's intentionally idempotent-safe. The first `release`
call's CAS succeeds (`LOCKED` with matching `lockId` → `AVAILABLE`); the
second call reads the *new* current state (`AVAILABLE`, no lock), finds
`current.status() != LOCKED`, and returns `false` immediately without
attempting any CAS — a clean, side-effect-free no-op. This matters
concretely because both `InMemorySeatLockService.releaseLock` (explicit,
client-initiated) and the reaper's `sweepExpiredLocks` (background,
time-triggered) can legitimately race to release the *same* lock (e.g., a
user cancels right as the TTL reaper also decides it's expired) — the
conditional `activeLocks.remove(lockId, lock)` ensures only one of them
actually proceeds to call `ShowSeat.release`, but even if both somehow
did, the second `release` call's harmless `false` return means no
incorrect state results either way.

**22. Why is the seat-lock reaper a single daemon thread rather than a thread pool?**
The reaper's work (iterating `activeLocks` and releasing anything
expired) is cheap, infrequent (a configurable fixed interval, 50ms–few
seconds), and has no benefit from parallelization — splitting one
iteration of a modest-sized map across multiple threads would add
coordination overhead (partitioning the map, waiting for all partitions
to finish) that dwarfs the actual work being parallelized. A single
thread also trivially avoids the reaper racing *itself* (two reaper runs
overlapping and double-processing the same expired lock), which a
multi-threaded reaper would need explicit coordination to prevent. Daemon
(not user) thread status ensures the reaper never prevents JVM shutdown
if `seatLockService.shutdown()` is forgotten — a defensive choice, though
`shutdown()` should still always be called explicitly for a clean stop.

---

## Database and Persistence

**23. If this moved to a real database, would you implement the seat lock as a database-level row lock (`SELECT ... FOR UPDATE`) or as the optimistic `version`-column approach described in README §12 — and why?**
Optimistic (`version`-column), for the same underlying reason CAS was
chosen over `synchronized` in-memory: `SELECT ... FOR UPDATE` is a
*pessimistic* lock — it holds a real database row lock for the duration
of the transaction, meaning if the transaction does anything slow between
acquiring the lock and committing (even just application-level logic, not
necessarily another DB call), every other transaction wanting that same
row blocks for that entire duration. The optimistic
`UPDATE ... WHERE version = ? AND status = 'AVAILABLE'` approach never
holds a lock across any meaningful span of time — each attempt is one
single, fast conditional write, mirroring the in-memory CAS's
no-blocking-ever property. It would only make sense to reach for
pessimistic row locks if the expected contention per seat were so
extreme that most optimistic attempts would fail and retry repeatedly
(classic optimistic-vs-pessimistic trade-off: optimistic wins when
conflicts are rare relative to attempts, pessimistic wins when conflicts
are the common case) — and for seat booking, the vast majority of seats
at any moment have zero contention, making optimistic the clear right
default, with only a small number of genuinely hot seats seeing any
retries at all.

**24. The README's proposed `booking` table has `idempotency_key VARCHAR(128) NOT NULL UNIQUE`. What happens, at the database level, if two instances race to insert two different bookings with the same key?**
Exactly one `INSERT` succeeds; the second violates the unique constraint
and the database rejects it with a constraint-violation error (e.g.,
Postgres's `23505 unique_violation`). The application-level handling for
that specific error code should catch it and re-query
`SELECT * FROM booking WHERE idempotency_key = ?` to fetch and return the
*other* instance's successfully-inserted booking — exactly mirroring what
the in-memory `BookingRepository.findByIdempotencyKey` check does
proactively (check-before-insert) in-process; the DB-level unique
constraint is the distributed-safe backstop for the same guarantee,
needed because two separate application instances can't share one
in-memory map to check against each other.

**25. Would you put the `show_seat` table in the same database/schema as `booking`, or separate them (e.g., a faster in-memory/Redis store for hot seat state, durable Postgres for bookings)?**
A hybrid is a very reasonable production answer, worth raising even
though this LLD doesn't implement it: seat *availability* state
(`show_seat`) is accessed far more frequently (every seat-map view, every
lock attempt) and needs the lowest possible latency, while booking
records need strong durability guarantees (financial/audit
requirements) more than raw speed. Splitting them — e.g., Redis (or an
in-memory grid) as the source of truth for live seat-lock state with a
short TTL already built into the data model itself, and Postgres as the
durable system of record for confirmed bookings/payments — is a common
real-world pattern. The risk to manage explicitly: a reconciliation path
for "Redis says BOOKED but Postgres has no CONFIRMED booking row" (e.g.,
after a crash) — this is the same crash-recovery concern raised in
`STAFF_LEVEL_DEEP_DIVE.md` Q2, just relocated to a two-store topology.

---

## Performance

**26. If profiling showed the CAS retry loop in `ShowSeat.tryLock` spinning more than expected under real load, what would you investigate first?**
First, whether "spinning" is actually happening at the `ShowSeat` level
at all, versus contention *upstream* — e.g., if `BookingService` or an
API layer is retrying an entire `initiateBooking` call (not just one
CAS) on `SeatUnavailableException`, that's a different, much more
expensive kind of "spin" (full method call + exception + new UUID
generation, not a cheap CPU-level retry) masquerading as CAS contention.
Assuming it's genuinely the `ShowSeat`-level loop, the fix is almost
never "add backoff to the CAS loop" (which would hurt latency for the
*common*, uncontended case to help a rare, already-fast-failing
contended case) — it's more likely a sign that far too many concurrent
attempts are landing on the exact same seat (a UI/client-side problem —
e.g., a buggy client auto-retrying a failed lock attempt in a tight
loop) rather than a server-side algorithmic problem to fix in
`ShowSeat` itself.

**27. Is there a way to make `CatalogService.getSeatMap` faster for a very large screen (say, a 500-seat IMAX auditorium) without changing its correctness?**
The current implementation streams and maps every `ShowSeat` for the show
on every call — O(seat count), which is fine at hundreds of seats but
wouldn't scale gracefully to, say, computing this for thousands of
simultaneous viewers of the same popular show's seat map. A real
optimization (not built here, since it's an operational/caching concern
orthogonal to the core design) would be a short-TTL (a few hundred
milliseconds) cache of the rendered seat map per show, invalidated
proactively on any lock/confirm/release event via the existing
`BookingEventListener`/`onLockExpired` hooks rather than purely by TTL —
turning a guaranteed-fresh-but-O(n)-every-time read into an
almost-always-cache-hit read with bounded staleness, which is an
acceptable trade-off for a *display* path (see
`STAFF_LEVEL_DEEP_DIVE.md` Q22 on why display-path staleness is
acceptable here).

**28. How would you benchmark the claim that the CAS-based approach actually outperforms a `synchronized`-based one for this workload, rather than just asserting it?**
A microbenchmark (JMH, specifically, to avoid common pitfalls of hand-rolled
timing loops — JIT warm-up, dead-code elimination) comparing two
`ShowSeat` variants — the real CAS-based one and a `synchronized`-method
variant with identical external behavior — under a range of thread
counts (low contention: 2–4 threads on different seats; high contention:
many threads on *one* seat) would be the rigorous way to validate the
claim rather than assert it from first principles. The expected result
curve: at low/no contention, CAS and `synchronized` should perform
similarly (uncontended `synchronized` in modern JVMs is already very
cheap via biased/thin locking); the real divergence should appear at high
contention on a *single* seat, where `synchronized` forces OS-level
thread parking/unparking for losing threads while CAS losers simply
fail fast and return — that's the specific, measurable claim worth
benchmarking rather than taking on faith.

---

## Failure Handling

**29. Besides the scenarios already covered in README §13, what happens if `BookingEventListener.onBookingConfirmed` itself throws an exception?**
As written, `BookingService.confirmBooking`'s
`listeners.forEach(l -> l.onBookingConfirmed(booking))` call has no
try/catch around it — a misbehaving listener (e.g., a notification
service throwing on a malformed email address) would propagate the
exception out of `confirmBooking` *after* the booking has already been
durably transitioned to `CONFIRMED` and payment has already succeeded —
meaning the caller would see an exception for what was, from the
business's perspective, a fully successful booking. This is a real,
worth-naming gap: production code should wrap each listener invocation in
its own try/catch (logging the failure, never letting one listener's bug
affect another listener or the caller's view of booking success), since
notification-on-confirmation is fundamentally a best-effort side effect,
not part of the core transactional guarantee the method promises.

**30. If the `MockPaymentGateway`'s `refund` call "fails" (hypothetically, if it were a real gateway and threw), what's the current behavior in `cancelBooking`, and is it acceptable?**
Currently, `cancelBooking` calls `paymentGateway.refund(...)` with no
try/catch, so a real gateway throwing there would propagate out of
`cancelBooking` *after* the booking has already been transitioned to
`CANCELLED` and the seats already released back to `AVAILABLE` — meaning
the seat is correctly freed and re-bookable, but the refund may not have
actually happened, and the caller sees an exception despite most of the
cancellation having "succeeded." This is not acceptable as a final
production answer (a customer could be left un-refunded with no record of
needing one) — the fix is to make `Payment.markRefunded()` conditional on
`refund()` actually succeeding, and to introduce a distinct
`REFUND_PENDING`/`REFUND_FAILED` payment status with a reconciliation
job that retries failed refunds asynchronously, rather than coupling
refund success to the synchronous cancellation call succeeding end to
end. Flagged here explicitly as a known simplification of the mock
gateway, not a hidden gap.
