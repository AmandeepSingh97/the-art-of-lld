# BookMyShow — Movie Ticket Booking System

A Staff-level low-level design (LLD) of a movie-ticket booking platform
(cities → theatres → screens → shows → seats), with the single hardest
interview requirement as the centerpiece: **preventing two users from
booking the same seat under concurrent requests**, plus temporary
seat-holds with TTL, idempotent payment confirmation, dynamic pricing,
search/filter, and cancellation/refund.

Full, compiling Java 17 source lives in `src/main/java/lld/bookmyshow`.
JUnit 5 tests live in `tests/lld/bookmyshow`. Both are verified to compile
and pass — see `IMPLEMENTATION_GUIDE.md` for how to reproduce the build.

---

## 1. Problem Understanding

**Plain explanation.** A user picks a city, a movie, and a showtime, sees a
seat map for that specific show, selects seats, pays, and gets a confirmed
ticket. Behind this simple flow sits the thing that makes this problem
interview-worthy: many users can be looking at the same seat map at the
same instant, and the system must guarantee that exactly one of them ends
up with that seat — never zero (seat wrongly shown as taken forever) and
never two (double-sold seat, the worst possible outcome for a ticketing
business).

**Real-world use case.** BookMyShow, Fandango, AMC Theatres' app — any
system where a physical, scarce, uniquely-identified resource (a seat) is
sold to the first of many simultaneous bidders, with a brief
reservation/payment window in between selection and confirmation.

**What the interviewer is evaluating.**
- Can you correctly identify where the *real* concurrency problem is
  (seat-level state, not "the whole system")?
- Do you choose a concurrency control mechanism and justify it (optimistic
  vs. pessimistic, lock granularity), rather than hand-waving "we'll use a
  lock"?
- Do you handle the *temporary hold* problem: a seat can't be locked
  forever just because someone opened the checkout page and walked away.
- Do you make booking confirmation idempotent, because real payment flows
  retry.
- Do you keep the object model clean (SOLID, composition) without
  over-engineering a ticketing micro-framework.

**In scope.**
- City/theatre/screen/seat catalog modelling.
- Show scheduling with per-category base pricing.
- Seat-level concurrency control: hold (lock) with TTL, confirm, release,
  cancel.
- Idempotent booking initiation and confirmation.
- A pluggable payment gateway abstraction (mocked).
- Pluggable pricing strategy (fixed and a simple dynamic/surge variant).
- Search/filter shows by movie, city, and time window.
- Cancellation with refund.

**Out of scope** (called out explicitly, not silently ignored).
- Persistent storage / real database (in-memory repositories only — see
  §12 for why, and what changes for production).
- Distributed coordination (Redis/Zookeeper-based locks, multi-node
  consistency) — this is a single-process LLD; §14 and
  `STAFF_LEVEL_DEEP_DIVE.md` cover how it would extend to a fleet of
  booking-service instances.
- Real payment gateway integration (Stripe/Razorpay SDK specifics).
- Seat-map UI/rendering, recommendation, user auth/session management,
  loyalty points, multi-currency, taxes.
- Admin workflows (theatre onboarding, show scheduling UI) beyond the
  domain objects needed to *represent* that data.

---

## 2. Requirement Gathering

### Functional — essential (interview-necessary)
1. Browse/search shows by movie, city, and date/time window.
2. View a show's live seat map: which seats are available / held / booked,
   and their price.
3. Hold (lock) a chosen set of seats for a bounded time while the user
   checks out.
4. Pay for a held set of seats and receive a confirmed booking — exactly
   once, even if the client retries the request.
5. Prevent any two users from ever holding or booking the same seat for
   the same show at the same time.
6. Cancel a confirmed booking and receive a refund; the seats become
   available again.
7. Automatically release a hold if the user abandons checkout before
   paying (TTL expiry).

### Functional — optional / production-extension (explicitly deferred)
- Group/family booking discounts, coupons, wallet credits.
- Waitlist when a show is sold out.
- Partial refund policies based on time-to-show.
- Multi-language/multi-currency pricing.
- Notifications (SMS/email/push) beyond a stubbed listener interface.

### Non-functional
| Concern | Requirement | How addressed |
|---|---|---|
| **Performance** | Seat-lock acquisition must be sub-millisecond under contention for a single seat. | Lock-free CAS on `ShowSeat` (no blocking monitor); see §11. |
| **Scalability** | Must handle a popular show's "seat map opens, thousands click the same few good seats" thundering-herd pattern without serializing unrelated seats. | Per-seat atomic state (no shared lock across seats); `ConcurrentHashMap`-backed inventory keyed by `(showId, seatId)`. |
| **Availability** | A slow/failed payment call for one booking must not block other users' seat locking/booking. | Payment call happens *after* the seat lock is already acquired, and failure releases only that caller's seats — no cross-request blocking. |
| **Consistency** | Seat state must never show a seat as available to two confirmed bookings simultaneously (linearizable per-seat). | CAS-based state machine gives linearizable transitions per seat; see §5 and §11. |
| **Extensibility** | Pricing rules and payment providers must be swappable without touching booking orchestration. | Strategy pattern for pricing; interface for payment gateway. |
| **Reliability** | A held seat must not be lost forever if the confirming request crashes mid-flight. | TTL + background reaper + lazy reclaim (belt-and-suspenders, §13). |
| **Thread-safety** | Every mutable shared object must have an explicit, justified concurrency strategy — no "probably fine". | See §11 for a per-class justification table. |

### Interview-necessary vs. production-extension split
This README implements and explains, in full, the interview-necessary
slice: correct seat concurrency, holds with TTL, idempotent confirm,
pricing strategy, search, cancellation. Where a production system would
add more (persistence, distributed locks, observability, real payment
SDKs), that is explicitly called out as **Production Evolution** (§17) —
not quietly bundled into the core design, and not omitted without
comment.

---

## 3. Clarifying Questions

These are the questions a Staff candidate should ask in the first 5
minutes, with the assumption this project ultimately adopted and why it
matters.

1. **"Is a seat locked (soft-held) when a user clicks it, or only when
   they start payment?"**
   *Why it matters:* Determines when the TTL clock starts and how long a
   user realistically holds inventory hostage. *Assumption adopted:* the
   lock is acquired when the user proceeds to checkout with a specific
   seat selection (`initiateBooking`), not on a tentative seat-map click —
   clicking is a read-only UI action against `CatalogService`.

2. **"What's an acceptable hold duration, and is it fixed or
   configurable per show/theatre?"**
   *Why it matters:* Too short frustrates users mid-payment; too long
   starves inventory during high demand. *Assumption:* a single
   configurable TTL (5 minutes in the demo), passed into `BookingService`
   — trivially made per-show/per-theatre by threading a value through
   `Show` instead of a constant.

3. **"Can a single booking span multiple seats, and must they all succeed
   or none (atomic group hold)?"**
   *Why it matters:* Group bookings (families, couples) are the common
   case; partial success (3 of 4 seats) is a terrible UX and a support
   nightmare. *Assumption:* yes, multi-seat bookings are first-class, and
   `SeatLockService.lockSeats` is all-or-nothing (§11 details the
   rollback mechanism).

4. **"Is the booking confirmation request guaranteed to be sent exactly
   once by the client, or must the server tolerate retries/duplicates?"**
   *Why it matters:* Real clients (mobile apps on flaky networks, browser
   double-clicks) *will* retry. If the server isn't idempotent, a retry
   after a slow-but-successful first charge can double-charge or
   double-book. *Assumption:* server must be idempotent; every
   state-changing call takes a client-supplied idempotency key (§11, §13).

5. **"Does cancellation need to support partial refunds based on how
   close to showtime it is?"**
   *Why it matters:* Changes refund math and whether `cancelBooking` needs
   a pricing-adjacent "refund policy" strategy. *Assumption:* full refund
   on cancellation (simplest, interview-reasonable default); a
   `RefundPolicy` strategy seam is called out as a trivial extension point
   in §16, not built speculatively.

6. **"Is pricing fixed per category, or does it vary with demand/time (surge)?"**
   *Why it matters:* Determines whether `PricingStrategy` needs to be an
   extensible interface from day one or can be a constant lookup.
   *Assumption:* both are supported via `PricingStrategy`
   (`FixedPricingStrategy` default, `DynamicPricingStrategy` as a
   demonstrated alternative), decided by the deployer, not hardcoded.

7. **"Single process/instance, or must this design account for multiple
   booking-service instances behind a load balancer?"**
   *Why it matters:* An in-process `AtomicReference`/`ConcurrentHashMap`
   solution is correct and fast for one instance but does **not**
   coordinate across instances. *Assumption:* this LLD targets a single
   process (standard for an LLD round); §14/`STAFF_LEVEL_DEEP_DIVE.md`
   explicitly address what must change (distributed lock / DB-row-level
   locking) for a multi-instance deployment, so the gap is acknowledged,
   not hidden.

8. **"Do we need to search/filter shows by language, format (2D/3D/IMAX),
   or only by movie/city/time?"**
   *Why it matters:* More filter dimensions change the search index shape.
   *Assumption:* movie/city/time-window filtering is implemented; language
   is modelled on `Movie` and trivially addable as a filter predicate —
   not built as a generic query DSL, which would be over-engineering for
   this exercise.

---

## 4. Domain Modelling

### Entities (identity-bearing, carry a lifecycle)
| Entity | Responsibility | Mutability |
|---|---|---|
| `City` | A city that hosts theatres. | Immutable. |
| `Theatre` | A physical cinema; owns its `Screen`s (composition). | Screens list grows at setup; otherwise immutable. |
| `Screen` | One auditorium; owns its fixed `Seat` layout (composition). | Seats list grows at setup; otherwise immutable. |
| `Seat` | A physical seat: row/number/category. | Fully immutable — never changes after layout. |
| `Movie` | A film that can be scheduled into many shows. | Immutable. |
| `Show` | One scheduled screening: movie + screen + time + base prices. | Immutable. |
| `ShowSeat` | **The concurrency-critical entity**: per-(show, seat) availability state. | Mutable, CAS-guarded. |
| `Booking` | Aggregate root for one booking attempt/lifecycle. | Mutable status, CAS-guarded. |
| `Payment` | One payment attempt tied to a booking. | Mutable status, atomic-guarded. |
| `User` | A registered end user. | Immutable. |

### Value objects (no independent identity/lifecycle beyond their owner)
- `SeatLock` — an immutable grant returned after a successful hold; the
  *source of truth* for whether it's still valid is always the live
  `ShowSeat` state, not this snapshot.
- `ShowSeat.LockState` — an immutable bundle of (status, lockId, ownerId,
  expiry) swapped atomically as one unit.
- `PaymentGateway.PaymentResult` — outcome of one charge attempt.
- `CatalogService.SeatAvailabilityView` — a read-only DTO for seat-map UI.

### Enums
- `SeatCategory` (SILVER/GOLD/PLATINUM + price multiplier).
- `SeatStatus` (AVAILABLE/LOCKED/BOOKED) — per-show-seat state.
- `BookingStatus` (CREATED/SEATS_LOCKED/PAYMENT_PENDING/CONFIRMED/
  CANCELLED/EXPIRED/FAILED) — booking lifecycle.
- `PaymentStatus` (INITIATED/SUCCESS/FAILED/REFUNDED).

### Interfaces (contracts/seams)
- `SeatLockService` — hold/confirm/release seats; the concurrency
  boundary.
- `PaymentGateway` — charge/refund, decoupled from any specific provider.
- `PricingStrategy` — price a seat category on a show at a point in time.
- `Repository<ID,T>` — minimal CRUD contract for the in-memory stores.
- `BookingEventListener` — observer hook for booking lifecycle events.

### Domain services
- `CatalogService` — read-only browsing/search (no mutation).
- `BookingService` — orchestrates the hold → pay → confirm → cancel
  workflow; the only component that composes `SeatLockService` +
  `PaymentGateway` + `PricingStrategy` together.
- `InMemorySeatLockService` — the seat-concurrency engine.

### Repositories
`CityRepository`, `TheatreRepository`, `MovieRepository`, `ShowRepository`,
`BookingRepository`, `PaymentRepository` (all thin `InMemoryRepository<ID,T>`
subclasses), plus the structurally different `SeatInventoryRepository`
(keyed by `(showId, seatId)`, not a flat `ID -> entity` map, because seat
inventory is naturally scoped per show).

### Factories / builders
Deliberately **not** used. `City`/`Theatre`/`Screen`/`Seat`/`Movie`/`Show`
construction is simple enough (a handful of required fields, no
conditional construction logic, no product families) that a constructor
is clearer than a `ShowFactory` or builder would be — see §7 for the
explicit factory-pattern rejection rationale.

### Strategies
`PricingStrategy` → `FixedPricingStrategy`, `DynamicPricingStrategy` (see
§7).

### Ownership / lifecycle / composition vs. association
- **Composition** (owner manages the child's lifecycle, child cannot
  outlive/exist independently of a meaningful owner in this model):
  `Theatre` *-- `Screen`, `Screen` *-- `Seat`.
- **Association** (reference without lifecycle ownership): `Show` → `Movie`,
  `Show` → `Screen`, `Theatre` → `City`, `Booking` → `Show`/`User`.
- **Derived/computed, not owned**: `ShowSeat` is created *for* a `Show`
  from its `Screen`'s seats at show-scheduling time
  (`SeatInventoryRepository.initializeInventory`), but is stored and keyed
  independently per show — one physical `Seat` maps to many `ShowSeat`
  instances (one per show it appears in).

---

## 5. Domain Invariants

Business rules that must **always** hold, and where they're enforced:

1. **A seat can be held by at most one lock at a time, for one show.**
   Enforced by `ShowSeat.tryLock`'s single-CAS transition
   `AVAILABLE -> LOCKED`; any other outcome for a racing thread is a `false`
   return, never a torn/ambiguous state. (`ShowSeat.java`)

2. **A seat can only be confirmed BOOKED by the exact lock that acquired
   it, and only before that lock expires.**
   Enforced by `ShowSeat.confirm` checking both `lockId` equality and
   `now <= expiry` before the CAS. (`ShowSeat.java`)

3. **A multi-seat booking either holds all requested seats or none.**
   Enforced by `InMemorySeatLockService.lockSeats`'s rollback-on-partial-
   failure loop. (`InMemorySeatLockService.java`)

4. **A booking's state transitions follow a strict forward-only state
   machine; no transition skips a required step or reverses a terminal
   state.**
   Enforced by `Booking.transitionTo`'s table-driven guard, which throws
   `InvalidBookingStateException` on any disallowed move. (`Booking.java`)

5. **The same idempotency key never results in two distinct bookings or
   two distinct charges.**
   Enforced by `BookingRepository.findByIdempotencyKey` (checked first in
   `initiateBooking`) and `PaymentGateway.charge`'s
   `computeIfAbsent`-based dedupe. (`BookingRepository.java`,
   `MockPaymentGateway.java`)

6. **A seat held but never confirmed within its TTL automatically becomes
   available again — inventory can never be silently lost.**
   Enforced redundantly: lazily by `ShowSeat.tryLock`'s expiry-reclaim
   check, and actively by `InMemorySeatLockService`'s background reaper.
   (`ShowSeat.java`, `InMemorySeatLockService.java`)

7. **A booking can only be cancelled from CONFIRMED, and cancelling always
   returns its seats to AVAILABLE and triggers a refund if payment
   succeeded.**
   Enforced by `Booking.transitionTo` (only `CONFIRMED -> CANCELLED` is
   legal) plus `BookingService.cancelBooking`'s unconditional
   seat-release + refund-if-paid logic. (`Booking.java`, `BookingService.java`)

8. **The price charged for a booking equals the sum of
   `PricingStrategy.priceFor(...)` for each seat at initiation time** — the
   amount is computed once and stored on `Booking`, not recomputed at
   confirm time (so a demand-based price swing between hold and payment
   can't silently change what the user is charged). (`BookingService.computeAmount`)

---

## 6. Architecture and Class Diagram

- Component diagram: [`diagrams/01-component-diagram.md`](diagrams/01-component-diagram.md)
- Class diagram: [`diagrams/02-class-diagram.md`](diagrams/02-class-diagram.md)
- Sequence diagrams (5 key flows — happy path, concurrent race, TTL
  expiry, payment failure, cancellation): [`diagrams/03-sequence-diagrams.md`](diagrams/03-sequence-diagrams.md)

These diagrams are kept in lockstep with `src/main/java/lld/bookmyshow` —
every class, method, and transition shown is real, compiling code, not
aspirational pseudocode.

---

## 7. Design Patterns

For each pattern actually used: why, where, the alternative considered,
and why the alternative was rejected.

### Strategy — `PricingStrategy`
- **Why:** Pricing rules (fixed vs. demand-based surge) are an axis of
  variation completely orthogonal to booking orchestration; interview
  discussions frequently probe "how would you add surge pricing later?".
- **Where:** `PricingStrategy` interface; `FixedPricingStrategy`
  (default), `DynamicPricingStrategy` (occupancy + time-to-show surge).
  Injected into both `CatalogService` and `BookingService`.
- **Alternative considered:** an `if/else`/`switch` on a `PricingMode` enum
  inside `BookingService`.
- **Why rejected:** that violates Open/Closed — adding a third pricing
  model means editing `BookingService` itself, and it couples unrelated
  pricing-math changes to the orchestration class that also handles
  payment and locking, raising the blast radius of a pricing bug.

### Observer — `BookingEventListener`
- **Why:** Notification/analytics/cache-invalidation concerns naturally
  react to booking lifecycle events without needing to be awaited
  synchronously inside the critical path, and without `BookingService`
  needing to know they exist.
- **Where:** `BookingService` holds a `CopyOnWriteArrayList<BookingEventListener>`
  and fires `onBookingConfirmed/Cancelled/Expired`. `ConsoleBookingEventListener`
  is the demo implementation.
- **Alternative considered:** returning an event/result object from each
  method and letting the caller fan it out.
- **Why rejected:** that pushes fan-out responsibility onto every caller
  of `BookingService`, and doesn't compose with the TTL-expiry path (which
  has no "caller" at all — it's driven by a background reaper, not a
  request). An internal observer list handles both uniformly.

### Repository — `Repository<ID,T>` / `InMemoryRepository<ID,T>`
- **Why:** Isolates persistence mechanics from domain/service logic, so
  swapping in-memory maps for a real database later (§17) doesn't touch
  `BookingService`/`CatalogService` call sites.
- **Where:** All flat-keyed entities (`City`, `Theatre`, `Movie`, `Show`,
  `Booking`, `Payment`).
- **Alternative considered:** a generic `GenericDao<T>` with
  query-by-example/specification support.
- **Why rejected:** no requirement in this problem needs arbitrary
  predicate queries beyond the few named ones (`search`,
  `findByIdempotencyKey`, `findByCityId`); a generic specification API
  would be speculative complexity with no caller.

### What was considered and explicitly **rejected**

- **State pattern (one class per `BookingStatus`)** — rejected because the
  only per-state behavioural difference here is "which transitions are
  legal", not divergent logic with its own fields/methods per state. A
  table-driven `EnumMap<BookingStatus, EnumSet<BookingStatus>>` guard
  (`Booking.transitionTo`) captures the same correctness guarantee with a
  fraction of the class-per-state ceremony. State pattern would be the
  right call if, say, `SEATS_LOCKED` and `PAYMENT_PENDING` had genuinely
  different *behavior* for the same method (e.g., `cancel()` meant
  something structurally different in each) rather than just different
  *legality*.

- **Factory pattern for `Show`/`Seat`/`ShowSeat` construction** — rejected;
  construction is a flat set of required constructor args with no
  conditional logic, subclassing, or product-family selection. A factory
  here would be ceremony without payoff (textbook over-engineering smell).

- **Singleton for repositories/services** — rejected. Every repository and
  service is constructed once via manual dependency injection in
  `BookMyShowDemo`/tests and passed down; a `getInstance()` singleton
  would make every test need to share global mutable state (or reset it),
  which is exactly the kind of hidden coupling that makes concurrency bugs
  hard to reproduce and tests flaky/order-dependent.

- **Command pattern for booking operations** — rejected; there's no
  requirement for undo/redo, queuing, or deferred execution of booking
  operations — they execute synchronously and immediately. Adding a
  `Command` wrapper around each method call would add a layer of
  indirection with no behavior it enables.

- **Pessimistic locking (`synchronized`/`ReentrantLock` per seat) as the
  *primary* mechanism** — considered and discussed in depth in §11/§16,
  ultimately not the primary approach (lock-free CAS was chosen instead),
  but intentionally *not* "rejected and forgotten" — it's a legitimate
  alternative with real trade-offs, covered honestly rather than
  dismissed.

---

## 8. API and Method Design

All public service methods, grouped by service. Exceptions map 1:1 to
classes in `lld.bookmyshow.exception`.

### `CatalogService` (read-only)
```java
List<Show> searchShows(String movieId, String cityId, Instant from, Instant to);
Show getShow(String showId);                              // throws ShowNotFoundException
List<SeatAvailabilityView> getSeatMap(String showId);      // throws ShowNotFoundException
```
- `movieId`/`cityId` are nullable filters (null = "any"); `from`/`to`
  bound the search window — validation of a sane window is the caller's
  (API-layer) responsibility, consistent with keeping this service free
  of HTTP-layer concerns.

### `BookingService` (the write path)
```java
Booking initiateBooking(String userId, String showId, List<String> seatIds, String idempotencyKey);
// throws ShowNotFoundException, SeatUnavailableException, IllegalArgumentException (empty seatIds)

Booking confirmBooking(String bookingId, String idempotencyKey);
// throws BookingNotFoundException, InvalidBookingStateException,
//        PaymentFailedException, SeatLockExpiredException

Booking cancelBooking(String bookingId);
// throws BookingNotFoundException, InvalidBookingStateException

Booking getBooking(String bookingId);
// throws BookingNotFoundException

void addListener(BookingEventListener listener);
```
- **Validation responsibilities:** `BookingService` validates
  *domain*-level preconditions (show exists, booking exists, current
  state permits the requested transition). It deliberately does **not**
  validate transport-level concerns (auth, request shape, rate limits) —
  those belong to an API/controller layer this LLD does not model.
- **Request/response shape:** kept as plain method parameters/returns
  (`Booking`) rather than wrapper DTOs, since this is a service-layer API,
  not a REST contract; an HTTP controller wrapping this would translate
  `Booking` to a response DTO and these exceptions to status codes (409 for
  `SeatUnavailableException`/`SeatLockExpiredException`, 402/422 for
  `PaymentFailedException`, 404 for the `*NotFoundException`s, 409/400 for
  `InvalidBookingStateException`).

### `SeatLockService` (the concurrency boundary)
```java
SeatLock lockSeats(String showId, List<String> seatIds, String ownerId, Duration ttl);
// throws SeatUnavailableException

boolean confirmLock(String lockId);   // false, not exception, on expired/unknown lock
void releaseLock(String lockId);      // no-op if lock unknown/already resolved
void onLockExpired(Consumer<SeatLock> listener);
void shutdown();
```
- `confirmLock`/`releaseLock` return booleans or no-op silently rather
  than throwing, because "the lock already expired" is an expected race
  outcome on this internal seam, not an exceptional one — reserving
  exceptions for the *caller-facing* API (`BookingService`) where a
  textual error must actually surface to a user.

### `PaymentGateway`
```java
PaymentResult charge(String idempotencyKey, BigDecimal amount);
void refund(String paymentReferenceId, BigDecimal amount);
```

### `PricingStrategy`
```java
BigDecimal priceFor(Show show, SeatCategory category, Instant now);
```

---

## 9. Core Implementation

Full source lives in `src/main/java/lld/bookmyshow`. The two most
important files, in full, are reproduced below because they carry the
entire concurrency correctness argument for this problem.

### `model/ShowSeat.java` — the seat-level CAS state machine

```java
public final class ShowSeat {

    public record LockState(SeatStatus status, String lockId, String ownerId, long expiryEpochMillis) {
        static LockState available() {
            return new LockState(SeatStatus.AVAILABLE, null, null, 0L);
        }
    }

    private final String showId;
    private final Seat seat;
    private final AtomicReference<LockState> stateRef = new AtomicReference<>(LockState.available());

    public boolean tryLock(String lockId, String ownerId, long expiryEpochMillis, long nowEpochMillis) {
        while (true) {
            LockState current = stateRef.get();
            LockState effective = reclaimIfExpired(current, nowEpochMillis);
            if (effective.status() != SeatStatus.AVAILABLE) {
                return false;
            }
            LockState next = new LockState(SeatStatus.LOCKED, lockId, ownerId, expiryEpochMillis);
            if (stateRef.compareAndSet(current, next)) {
                return true;
            }
            // Someone else mutated the state between our read and our CAS — retry.
        }
    }

    public boolean confirm(String lockId, long nowEpochMillis) {
        while (true) {
            LockState current = stateRef.get();
            if (current.status() != SeatStatus.LOCKED || !current.lockId().equals(lockId)) {
                return false;
            }
            if (nowEpochMillis > current.expiryEpochMillis()) {
                return false;
            }
            LockState next = new LockState(SeatStatus.BOOKED, null, null, 0L);
            if (stateRef.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    public boolean release(String lockId) {
        while (true) {
            LockState current = stateRef.get();
            if (current.status() != SeatStatus.LOCKED || !current.lockId().equals(lockId)) {
                return false;
            }
            if (stateRef.compareAndSet(current, LockState.available())) {
                return true;
            }
        }
    }

    public boolean cancelBooking() {
        while (true) {
            LockState current = stateRef.get();
            if (current.status() != SeatStatus.BOOKED) {
                return false;
            }
            if (stateRef.compareAndSet(current, LockState.available())) {
                return true;
            }
        }
    }

    private static LockState reclaimIfExpired(LockState state, long nowEpochMillis) {
        if (state.status() == SeatStatus.LOCKED && nowEpochMillis > state.expiryEpochMillis()) {
            return LockState.available();
        }
        return state;
    }
    // ... accessors omitted; see src/ for the full file
}
```

### `service/InMemorySeatLockService.java` — multi-seat orchestration (excerpt)

```java
public SeatLock lockSeats(String showId, List<String> seatIds, String ownerId, Duration ttl) {
    List<String> sortedSeatIds = seatIds.stream().sorted().toList();
    String lockId = IdGenerator.uuid();
    long nowMillis = clock.millis();
    long expiryMillis = nowMillis + ttl.toMillis();

    List<ShowSeat> acquired = new ArrayList<>(sortedSeatIds.size());
    for (String seatId : sortedSeatIds) {
        ShowSeat showSeat = seatInventoryRepository.findShowSeat(showId, seatId)
                .orElseThrow(() -> new SeatUnavailableException("Seat " + seatId + " does not exist"));
        if (!showSeat.tryLock(lockId, ownerId, expiryMillis, nowMillis)) {
            for (ShowSeat rollback : acquired) {
                rollback.release(lockId);     // undo partial acquisition — all-or-nothing
            }
            throw new SeatUnavailableException("Seat " + seatId + " is already locked or booked");
        }
        acquired.add(showSeat);
    }
    SeatLock lock = new SeatLock(lockId, showId, sortedSeatIds, ownerId, Instant.ofEpochMilli(expiryMillis));
    activeLocks.put(lockId, lock);
    return lock;
}
```

The remaining core classes (`Booking`'s transition table,
`BookingService`'s full orchestration including payment and the
lock-expiry callback, `MockPaymentGateway`'s idempotent charge, and the
pricing strategies) are in `src/main/java/lld/bookmyshow/{model,service,strategy}`
and are referenced throughout this README by file name — all are real,
compiling, unit-tested code, not pseudocode.

---

## 10. Data Structures and Algorithms

| Structure | Where | Why | Complexity |
|---|---|---|---|
| `AtomicReference<LockState>` | `ShowSeat` | Bundles 4 fields that must change together atomically into one lock-free CAS slot — the core of the no-double-booking guarantee. | `tryLock`/`confirm`/`release` are O(1) amortized; a CAS retry loop is bounded by actual contention (practically 1–2 iterations even under heavy load, since the critical section is a few CPU instructions). |
| `AtomicReference<BookingStatus>` + `EnumMap<BookingStatus, EnumSet<BookingStatus>>` | `Booking` | O(1) transition legality lookup via enum ordinal-indexed map; CAS prevents two racing transitions (e.g., confirm vs. expiry-sweep) from both "succeeding". | O(1) per transition attempt. |
| `ConcurrentHashMap<ID, T>` | All `InMemoryRepository` subclasses, `SeatInventoryRepository`'s outer map, `activeLocks`, `bookingIdByLockId` | Lock-striped concurrent map gives safe concurrent insert/lookup without a global lock; LinkedIn/Doug Lea-style segment design means unrelated keys (different shows, different bookings) never contend. | O(1) average get/put. |
| `ConcurrentHashMap.computeIfAbsent` | `MockPaymentGateway` | Per-key-atomic "compute once, everyone sees the same result" — exactly the semantics idempotent charging needs, without a global mutex across all payments. | O(1) average; the mapping function runs at most once per key even under concurrent callers. |
| Sorted seat-id list before acquisition | `InMemorySeatLockService.lockSeats` | Deterministic acquisition order across overlapping multi-seat requests (see §11) — not for correctness (CAS doesn't deadlock) but for predictable, explainable contention behavior. | O(n log n) sort over a small per-booking seat count (typically ≤ 10). |
| `CopyOnWriteArrayList<BookingEventListener>` / `<Consumer<SeatLock>>` | `BookingService`, `InMemorySeatLockService` | Listener lists are write-rarely (registered at startup), read-often (every booking event) — `CopyOnWriteArrayList` optimizes exactly for that access pattern, with no read-side locking at all. | O(1) iteration-time read, O(n) copy on the rare `add`. |
| Linear scan over `findAllForShow(showId).stream().filter(...)` | `ShowRepository.search`, `CatalogService.getSeatMap` | Show/seat counts per query are small (a city has dozens of shows, a screen has a few hundred seats) — a full scan with a stream filter is simpler and fast enough; a real production catalog would back this with an actual search index (§14). | O(n) over shows or seats in scope; acceptable at LLD/demo scale, explicitly flagged as a scaling bottleneck in §14. |

**Alternatives considered for the seat state machine:**
- A single `volatile SeatStatus` field — rejected: can't atomically also
  carry lock ownership/expiry, so a `tryLock` and a `confirm` could
  interleave incoherently (e.g., confirm a lock that was never actually
  this caller's).
- `synchronized` methods on `ShowSeat` — correct, but serializes all
  readers/writers of that seat behind one monitor and risks someone
  accidentally extending the critical section across a slow call; see
  §11/§16 for the full comparison.

---

## 11. Concurrency and Thread Safety

This is the section the interview is really testing. Every shared mutable
object in this system is enumerated below with its guarantee and the
justification for the mechanism chosen — nothing is asserted without
reasoning.

### 11.1 `ShowSeat` — the hot path, analyzed in full

**Shared mutable state:** one `AtomicReference<LockState>` per
`(showId, seatId)` pair.

**Race condition without protection:** Two threads read "seat A1 is
AVAILABLE" simultaneously, both decide to lock it, both write "LOCKED" —
both believe they succeeded; later both try to confirm, and now the seat
is sold twice. This is the canonical double-booking bug this whole design
exists to prevent.

**Mechanism chosen: lock-free optimistic concurrency (CAS loop).**
`tryLock`/`confirm`/`release`/`cancelBooking` all follow the pattern
*read current state → validate → compute next state → `compareAndSet`,
retry on failure*. Because `compareAndSet` is a single atomic hardware
instruction (backed by `LOCK CMPXCHG` on x86) comparing against the
*exact* reference read a moment earlier, if another thread mutated the
state in between, the CAS fails and the loop retries with a fresh read —
it is logically impossible for two threads to both believe they acquired
the same seat, because only one CAS against any given `current` snapshot
can succeed.

**Why CAS over `synchronized`/`ReentrantLock` here specifically:**
- No thread ever blocks waiting for another to release a monitor —
  important because `ShowSeat` operations must stay fast even if upstream
  callers (e.g. a slow `BookingService.confirmBooking` in a *different*
  request touching the *same* seat) are between CAS calls; the CAS itself
  never holds anything "open" that another thread must wait on.
- No deadlock risk from this class *in isolation* (a single
  `AtomicReference` has no lock-ordering problem). Multi-seat deadlock
  risk is a separate concern addressed in §11.2.
- Contention here is typically very short-lived (a handful of threads for
  a few microseconds around a popular seat's release/lock moment), which
  is exactly the regime where lock-free CAS outperforms blocking locks
  (no context-switch/park-unpark overhead).

**Where this would *not* be the right call:** if the critical section per
seat involved meaningfully more work (e.g., writing to disk while holding
the "lock"), a blocking lock with a short, bounded critical section would
be easier to reason about than a CAS retry loop around I/O. This system
deliberately keeps all CAS critical sections pure in-memory and O(1), so
that concern doesn't arise — see §13 for what happens to I/O (payment)
which is *always* done outside any lock.

**Liveness:** the retry loop can only livelock if an unbounded number of
threads keep successfully mutating the same seat forever, which would
also mean many bookings are actually succeeding — in a bounded-seat system
this terminates as soon as the seat becomes LOCKED/BOOKED (losers return
`false` immediately rather than retry, see `tryLock`'s single `return
false` on `effective.status() != AVAILABLE` — there is no retry-after-loss
behavior; the method completes after one failed CAS and a loser never
spins).

### 11.2 Multi-seat group locks (`InMemorySeatLockService.lockSeats`)

**Shared mutable state:** multiple independent `ShowSeat`s, acquired
within one logical "group lock" operation, plus the `activeLocks`
`ConcurrentHashMap`.

**Problem:** there is no multi-key CAS primitive, so "lock seats A1 and
A2 atomically" cannot be a single hardware operation. Composing two
single-seat CASes introduces a **partial-failure window**: A1 succeeds,
A2 fails (already held) — the caller must not be left holding A1 forever.

**Mechanism chosen:** acquire one seat at a time in **sorted seat-id
order**, and **roll back** (`release`) every already-acquired seat the
moment any acquisition fails.
- Sorting matters for *predictability*, not deadlock-avoidance (CAS can't
  deadlock): without a canonical order, two overlapping group-lock
  requests (user X wants {A1, A2}, user Y wants {A2, A1}) could interleave
  their acquisition attempts in a less consistent pattern. Sorting both
  to the same relative order makes the outcome of any given race
  deterministic and easy to reason about/test (see
  `InMemorySeatLockServiceTest.lockSeatsIsAllOrNothingAndRollsBackOnPartialConflict`).
- Rollback-on-failure is what gives the **all-or-nothing** guarantee
  despite the underlying primitive only being single-key atomic — this is
  a standard compensating-action pattern (the same idea as a saga), scoped
  down to an in-process, synchronous version since no network calls are
  involved.

**`activeLocks` map:** a `ConcurrentHashMap<lockId, SeatLock>`. Insert
(`put`) happens once, synchronously, right after all seats are
successfully acquired — never partially visible. Removal uses the
**conditional** `remove(key, value)` overload in the reaper
(`sweepExpiredLocks`) specifically to avoid a race where the reaper could
evict a *newer* lock that reused the same `lockId` slot after the original
was already confirmed/released and a fresh lock (improbably, same
`lockId` — practically impossible with UUIDs, but defensively correct
regardless) was created; more realistically, it prevents a benign but
wasteful double-release if `confirmLock`/`releaseLock` and the reaper
observe the same lock in the same sweep window.

### 11.3 `confirmLock` — the same partial-failure pattern, mirrored

Mirrors `lockSeats`: confirms seats one at a time, and if any single
seat's `confirm` fails (most likely: expired mid-confirmation), every
already-confirmed seat in this group is rolled back via `cancelBooking()`
(BOOKED → AVAILABLE) so the group-confirm is also all-or-nothing — a
booking is never left holding 3 of 4 confirmed seats.

### 11.4 `Booking` status — CAS-guarded state machine

**Shared mutable state:** `AtomicReference<BookingStatus>`.

**Race it prevents:** the TTL-expiry reaper callback and a user's
in-flight `confirmBooking` call can race on the *same* booking (user pays
in the last millisecond before expiry). `Booking.transitionTo`/
`tryTransitionTo` CAS against the state table — whichever thread's CAS
lands first wins, and the other either throws (`transitionTo`, used on the
user-facing path, where an invalid move should surface as an error) or
silently no-ops (`tryTransitionTo`, used by the expiry callback, which
should never throw on a background thread for an expected race outcome).
This asymmetry (throwing vs. non-throwing variant for the *same*
underlying table) is itself a deliberate, justified design choice, not an
oversight.

### 11.5 `Payment` status — `AtomicReference<PaymentStatus>`

Same category as `Booking`: a few terminal/near-terminal states, set from
at most one orchestration path at a time per payment (a given `Payment`
object is only ever touched by the one `BookingService.confirmBooking`/
`cancelBooking` call that created it) — `AtomicReference` here is simple
visibility+atomicity insurance rather than a response to realistic
contention, which is an honest distinction to draw in an interview (not
every atomic is defending against a *likely* race; some are defending
against a *possible* one at near-zero cost).

### 11.6 Repositories — `ConcurrentHashMap`

Safe concurrent insert/lookup for the map structure itself. No
repository ever needs to lock across a read-modify-write of an entity's
*internal* fields, because every mutable entity (`Booking`, `Payment`,
`ShowSeat`) manages its own internal synchronization — the repository
layer only ever does `put`/`get`, never "read a field, compute, write it
back" across map boundaries.

### 11.7 The background reaper thread

`InMemorySeatLockService` runs a single daemon `ScheduledExecutorService`
sweeping `activeLocks` on a fixed interval. Single-threaded by design —
there is no benefit to parallelizing a cheap periodic sweep, and
single-threading it trivially avoids the reaper racing *itself*.

### 11.8 Lock-ordering / deadlock analysis

No two threads in this system ever need to hold two *blocking* locks
simultaneously (there are no `synchronized` blocks or `ReentrantLock`s
anywhere in the core path) — deadlock in the classical "circular wait on
mutexes" sense is **structurally impossible** by construction. The only
multi-step coordination (`lockSeats` across several seats) uses rollback
on failure, not lock acquisition order on a blocking primitive, so there
is nothing to order against to avoid deadlock in the first place.

### 11.9 Memory visibility

`AtomicReference`/`ConcurrentHashMap`/`CopyOnWriteArrayList` all provide
the Java Memory Model's happens-before guarantee across threads for every
field this system needs visible: a `compareAndSet` that succeeds
establishes happens-before with every subsequent `get()` on that same
reference, so a thread that observes `BOOKED` is guaranteed to also
observe every other field change that was packaged into that same
`LockState`/`BookingStatus` write (trivial here since each is a single
atomic field, but worth stating explicitly: no plain field is ever read
by one thread after being written by another without going through one of
these constructs).

---

## 12. Persistence and Database Design

**This system is in-memory by design, and that is a deliberate, justified
choice for an LLD exercise — not an oversight.**

**Why a database is not required for the core interview answer:**
- The focus of this problem is *correctness under concurrency*, which is
  orthogonal to *durability*. An in-memory `ConcurrentHashMap` and a
  Postgres table with row-level locking (`SELECT ... FOR UPDATE`) face the
  exact same logical race condition and need the exact same conceptual
  answer (some form of atomic compare-and-swap on seat state); demonstrating
  the concurrency reasoning clearly in-process is the interview signal,
  not setting up a schema.
- Introducing a real DB (connection pooling, transaction boundaries,
  migrations) would roughly triple the code surface of this exercise
  without adding to the demonstrated reasoning about the actual hard
  problem.

**What the schema *would* look like in production** (for the
`STAFF_LEVEL_DEEP_DIVE.md`/§17 discussion of evolving this design):

```sql
CREATE TABLE show_seat (
    show_id        UUID NOT NULL,
    seat_id        UUID NOT NULL,
    status         VARCHAR(16) NOT NULL,   -- AVAILABLE | LOCKED | BOOKED
    lock_id        UUID NULL,
    lock_owner_id  UUID NULL,
    lock_expiry_at TIMESTAMPTZ NULL,
    version        BIGINT NOT NULL DEFAULT 0,  -- optimistic-lock column
    PRIMARY KEY (show_id, seat_id)
);
CREATE INDEX idx_show_seat_status ON show_seat (show_id, status);

CREATE TABLE booking (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL,
    show_id          UUID NOT NULL,
    seat_ids         UUID[] NOT NULL,
    lock_id          UUID NOT NULL,
    amount           NUMERIC(10,2) NOT NULL,
    idempotency_key  VARCHAR(128) NOT NULL UNIQUE,  -- DB-enforced uniqueness backstops the in-process check
    status           VARCHAR(20) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX idx_booking_idempotency ON booking (idempotency_key);
```

**Mapping the in-memory mechanism to a real DB:**
- `ShowSeat`'s `AtomicReference<LockState>` CAS becomes an
  **optimistic-locking** `UPDATE ... WHERE show_id=? AND seat_id=? AND
  version=? AND status='AVAILABLE'` — a zero-row-affected result is the
  CAS failure case, handled identically to `tryLock` returning `false`.
- The `idempotency_key UNIQUE` constraint is the DB-level backstop for
  what `BookingRepository.findByIdempotencyKey` does in-process — needed
  because in a multi-instance deployment, two instances could both miss
  their own in-memory check and race to insert; the DB constraint is the
  final arbiter (§16 covers this explicitly as a known gap of the
  in-memory version run on more than one instance).
- **Isolation level:** `READ COMMITTED` is sufficient given the optimistic
  `version`/`status` guard is explicit in the `WHERE` clause (equivalent
  to manually implementing the compare in the CAS); `SERIALIZABLE` would
  be unnecessary overhead for this access pattern.
- **Transactions:** the "charge payment, then confirm seats" sequence
  would span a payment-gateway call (external, not transactional) and a
  DB update (transactional) — exactly the reason `BookingService` already
  treats them as two separate steps with explicit compensating actions
  (refund on seat-confirm failure) rather than wrapping both in one DB
  transaction, since the external call cannot participate in a database
  transaction anyway. This in-memory design's shape already anticipates
  the real persistence design.

---

## 13. Failure Scenarios

| Scenario | Detection | Recovery |
|---|---|---|
| **Client abandons checkout after locking seats** | TTL elapses with no `confirmBooking` call. | Background reaper releases the seats (`ShowSeat.release`) and marks the booking `EXPIRED` via the registered callback; seats become bookable again automatically, no manual intervention. |
| **Payment gateway call fails/declines** | `PaymentResult.success() == false` from `PaymentGateway.charge`. | `BookingService.confirmBooking` releases the seat lock immediately and transitions the booking to `FAILED`; the seats are available to others within the same call, not after a timeout. |
| **Payment succeeds but the seat lock expired in the tiny window before `confirmLock` runs** (rare race: charge call itself took long enough to cross the TTL boundary) | `seatLockService.confirmLock` returns `false`. | `BookingService` immediately issues a refund (`paymentGateway.refund`), marks the `Payment` `REFUNDED` and the `Booking` `EXPIRED`, and surfaces `SeatLockExpiredException` to the caller — the customer is never left charged with no seats. |
| **Client retries `confirmBooking` after a network timeout on the first (successful) attempt** | `Booking.getStatus() == CONFIRMED` already. | Short-circuits and returns the existing confirmed booking; `PaymentGateway.charge`'s `computeIfAbsent` on the idempotency key additionally guarantees no double charge even if the retry reached the payment call. |
| **Client retries `initiateBooking` after a timeout** | `BookingRepository.findByIdempotencyKey` finds an existing booking. | Returns the existing booking without attempting to re-lock seats (which would likely fail anyway, since the first attempt's lock is still held). |
| **Duplicate/out-of-order concurrent requests for the exact same seat** | `ShowSeat.tryLock` CAS fails for every loser. | Losers receive `SeatUnavailableException` synchronously — the interview-critical invariant (§5, §11). |
| **Attempt to cancel an already-cancelled or never-confirmed booking** | `Booking.transitionTo` rejects the transition (not in the allowed-target set for the current state). | Throws `InvalidBookingStateException`; no partial side effects occur because the state check happens *before* any seat/payment mutation in `cancelBooking`. |
| **Resource exhaustion: a popular show's seat map gets thousands of concurrent lock attempts on the same handful of seats** | N/A — by design. | Lock-free CAS means losing threads fail fast (O(1), no blocking, no thread-pool starvation) rather than queuing behind a monitor; see §14 for the throughput argument. |
| **Reaper thread dies/is delayed** | N/A directly observable, but correctness doesn't depend on it. | `ShowSeat.tryLock`'s **lazy** expiry-reclaim path means a new lock attempt against an expired-but-not-yet-swept seat still succeeds — the system is self-healing even with a stalled reaper; the reaper only affects *how promptly* an idle seat becomes visibly available and *how promptly* the `onLockExpired` callback fires, not whether locking remains correct. |
| **Corrupt/invalid booking request (empty seat list)** | `lockSeats` throws `IllegalArgumentException` immediately. | Fails fast before any seat is touched — no partial locks to clean up. |
| **Unknown show/booking id** | Repository `findById` returns empty `Optional`. | `ShowNotFoundException`/`BookingNotFoundException` thrown with no side effects. |

---

## 14. Performance and Scalability

### Bottlenecks and fixes, single process
- **Hot seat contention** (a few seats everyone wants): already addressed
  by per-seat (not per-show, not global) CAS — unrelated seats on the same
  show never contend with each other at all. The only real contention is
  on the literal handful of seats actively being raced for at any instant,
  which is inherently small.
- **`ShowRepository.search`/`CatalogService.getSeatMap` linear scans**:
  acceptable at the scale of "shows in a city" / "seats in a screen" (tens
  to a few hundred), but would need a real index (e.g., a
  `(cityId, movieId, date) -> List<showId>` secondary index, or an actual
  search engine) once the catalog grows to thousands of shows — flagged
  here explicitly as a known scaling limit of the demo implementation, not
  silently left as-is.
- **Reaper sweep cost**: iterates all `activeLocks`, which scales with
  concurrently-mid-checkout users, not total seats/shows — bounded and
  small in practice; could be replaced with a time-ordered structure
  (e.g., a delay queue keyed by expiry) if the active-lock count ever
  became large enough to matter, trading O(n) sweep for O(log n) insert
  and O(1) "next expiring" pop.

### Multi-threaded (single process, many cores)
- The design already assumes and is correct under arbitrary thread-level
  parallelism within one JVM — that's the entire point of the CAS-based
  approach. No further change needed to scale from "a few threads" to
  "as many threads as the host has cores" for request handling.

### Distributed extension (explicitly *not* over-built here)
- A single JVM's `ConcurrentHashMap`/`AtomicReference` approach does
  **not** coordinate across multiple instances of this service behind a
  load balancer — two instances could each believe they won the same
  seat's lock. Options, with trade-offs:
  - **Sticky routing by showId** (route all requests for a given show to
    one instance) — keeps the in-memory design valid, but creates a
    hot-instance risk for a very popular single show and a failover gap
    if that instance dies mid-lock.
  - **Move `ShowSeat` state into a shared store with atomic
    compare-and-swap semantics** (Redis `SET ... NX` + Lua script for
    multi-key atomicity, or a relational DB with the optimistic `version`
    column from §12) — the natural evolution, same algorithmic shape,
    different substrate for the CAS.
  - **Distributed lock service** (Zookeeper/etcd) — more heavyweight than
    needed here; seat-locking is a short-lived, high-throughput operation,
    a poor fit for a general-purpose distributed-lock service's latency
    profile compared to a Redis-based atomic primitive.
  This system intentionally does **not** build any of these into the core
  answer — see `STAFF_LEVEL_DEEP_DIVE.md` for the full reasoning on why
  over-distributing an LLD answer is itself a design smell to avoid in an
  interview.

---

## 15. Testing Strategy

Tests live in `tests/lld/bookmyshow`, run with JUnit 5. Categories and
representative files:

- **Unit — seat state machine** (`ShowSeatTest`): every legal transition,
  every illegal transition returns `false` (not throws — this is an
  internal seam), wrong-lock-id rejection, expiry-based reclaim, and the
  dedicated concurrency test
  `exactlyOneThreadWinsWhenManyThreadsRaceForTheSameSeat` — 50 threads via
  `ExecutorService` + `CountDownLatch` race for one seat; asserts exactly
  1 success.
- **Unit — booking state machine** (`BookingStateTransitionTest`): valid
  forward path, rejection of transitions out of terminal states, the
  throwing vs. non-throwing (`tryTransitionTo`) distinction under a
  simulated late-arriving expiry callback.
- **Integration — seat-lock service** (`InMemorySeatLockServiceTest`):
  multi-seat all-or-nothing acquisition and rollback, confirm/release
  semantics, lazy expiry reclaim using an `AdjustableClock` (no
  `Thread.sleep` flakiness), and the **active background reaper** path
  (asserted via a real, short wall-clock wait since the reaper is a real
  daemon thread — the one test in the suite that legitimately needs real
  time, isolated and explained in its own comment).
- **Integration — booking workflow** (`BookingServiceTest`): happy path,
  idempotent `initiateBooking`/`confirmBooking` retries, payment failure
  releasing seats, cancellation + refund, rejecting cancellation of a
  non-confirmed booking, and lock-expiry surfacing as
  `SeatLockExpiredException`.
- **Concurrency — the headline test** (`ConcurrentBookingTest`):
  `exactlyOneConcurrentBookingAttemptSucceedsForTheSameSeat` drives 30
  threads through the **entire public `BookingService` API** (not just the
  low-level `ShowSeat`) — initiate + confirm, each with a real
  idempotency key and a real mock payment call — all racing for one seat,
  asserting exactly 1 `CONFIRMED` and 29 `SeatUnavailableException`
  rejections. A companion test,
  `concurrentBookingsOnDisjointSeatsAllSucceed`, proves the converse: seats
  that don't overlap never spuriously contend with each other.
- **Pricing** (`PricingStrategyTest`): fixed-category multiplier math, and
  both axes of the dynamic strategy (time-to-show surge, occupancy surge)
  verified as monotonic effects, not exact magic numbers (robust to minor
  tuning of the surge curve).
- **Boundary/negative cases covered throughout:** empty seat list,
  double-cancel, confirm-before-lock, confirm-with-wrong-lock-id,
  cancel-a-non-booked-seat, unknown booking/show ids.

**Verification performed for this deliverable:** compiled with `javac`
against `junit-platform-console-standalone.jar` and executed via the
JUnit Platform Console Launcher. **36/36 tests pass**, including the
concurrency-critical tests, run repeatedly to confirm no flakiness (see
`IMPLEMENTATION_GUIDE.md` for the exact commands and output).

---

## 16. Trade-offs and Alternatives

| Decision | Chosen approach | Alternative | Why chosen | When the alternative wins |
|---|---|---|---|---|
| Seat concurrency primitive | Lock-free CAS (`AtomicReference<LockState>`) | Pessimistic locking (`synchronized`/`ReentrantLock` per seat, or a DB `SELECT ... FOR UPDATE`) | No thread ever blocks; naturally scales with cores; short, pure in-memory critical sections are the ideal case for CAS. | If the critical section necessarily included slow work (e.g., a synchronous external call *inside* the seat mutation itself, rather than staged before/after it as this design does) — then a bounded blocking lock is easier to reason about than a CAS-retry-around-I/O anti-pattern. Also wins if the team's familiarity/tooling strongly favors locks (e.g., existing debugging tools for deadlock detection) over lock-free reasoning. |
| Multi-seat atomicity | Sequential acquire + rollback-on-failure | A single coarse-grained lock over the *entire show's* seat map during any group booking | Per-seat granularity lets unrelated seats/bookings proceed fully in parallel. | If group bookings were the *overwhelmingly* dominant case and single-seat bookings were rare, a per-show lock might be simpler to reason about at a small throughput cost — not the case here (single-seat bookings are the common case). |
| Idempotency mechanism | Client-supplied idempotency key, checked in both `BookingRepository` and `PaymentGateway` | Server-generated request id returned from a prior "intent" call that the client must store and resend | Client-supplied keys work across true client retries (e.g., app killed and relaunched) where no prior server response was ever received to extract a server-generated id from. | A server-generated id can be simpler to reason about for strictly single-request-response retry (no client-side id-generation correctness to depend on) if the client population is fully controlled (e.g., a single first-party mobile app you fully trust to retry correctly). |
| Pricing | Strategy interface with two implementations | A single pricing formula hardcoded into `BookingService` | Keeps pricing experimentation/surge logic decoupled from booking orchestration; directly answers a very common interview follow-up ("add dynamic pricing") without a redesign. | For a genuinely simple, never-changing flat-rate business (unlikely for a ticketing platform, but possible for, say, a single-screen community theatre), hardcoding is less code and there's no real "strategy" being selected between. |
| Booking lifecycle representation | Table-driven transition guard (`EnumMap`/`EnumSet`) on a plain `Booking` class | Full State design pattern (`BookingState` interface, one class per state) | No behavioral divergence per state beyond legality — a table captures that fully with far less ceremony. | If different states needed genuinely different *behavior* for the same operation (not just different *legality*) — e.g., if `cancel()` meant "refund 100%" in one state and "refund 50%" in another with real distinct logic per state — the State pattern's polymorphism would pay for itself. |
| Persistence | Pure in-memory, thread-safe collections | A real relational database from the start | Keeps the exercise focused on the concurrency reasoning (§12); DB mechanics are additive, not foundational, to the core problem. | Any real deployment, obviously — §12 and §17 spell out exactly what changes and why it maps cleanly onto the in-memory design's shape. |
| Expiry enforcement | Dual: lazy reclaim + active background reaper | Active reaper only | Correctness never depends on the reaper having run recently — self-healing even if the reaper thread is delayed or (hypothetically) dies. | Lazy-only (no reaper) would also be *correct* but would leave stale `LOCKED` entries sitting unreclaimed indefinitely absent new traffic on that exact seat, delaying when `onLockExpired` listeners (and thus booking-expiry bookkeeping) fire — the active reaper exists purely for *promptness*, not correctness, which is worth stating explicitly rather than conflating the two. |

---

## 17. Production Evolution

Kept deliberately separate from the core interview-focused design above —
these are the things a Staff engineer would raise as "here's what changes
once this ships to real users," not things that should have been built
into the exercise itself.

- **Persistence:** migrate `InMemoryRepository`/`SeatInventoryRepository`
  to real DB-backed repositories per the schema in §12; `ShowSeat`'s CAS
  becomes an optimistic-locking `UPDATE ... WHERE version=?`.
- **Distributed coordination:** see §14's distributed-extension options
  (sticky routing, Redis-backed atomic seat state, or a dedicated
  distributed-lock service) once this runs on more than one instance.
- **Monitoring/metrics:** booking funnel conversion (initiated → confirmed
  vs. expired vs. failed), seat-lock contention rate (CAS retry counts per
  seat per unit time — a direct signal of "hot" seats/shows), payment
  gateway latency/error-rate, reaper sweep duration and active-lock-count
  over time.
- **Logging:** structured, correlation-id-tagged logs through the
  `BookingEventListener` seam already present — a production
  implementation would emit structured events here instead of (or in
  addition to) `ConsoleBookingEventListener`'s `System.out`.
- **Fault tolerance:** payment gateway circuit breaker/retry-with-backoff
  around `PaymentGateway.charge` (currently a direct call); dead-letter
  handling for a refund call that itself fails after a lock-expiry race
  (§13) — today that would surface as an unhandled exception from
  `paymentGateway.refund`, and production code would need an explicit
  reconciliation/retry queue for that narrow case.
- **Operational concerns:** graceful shutdown ordering (drain in-flight
  bookings before `seatLockService.shutdown()`), schema
  migrations for the `show_seat`/`booking` tables, idempotency-key TTL
  cleanup (the UNIQUE index in §12 would need a retention policy so the
  table doesn't grow unbounded with keys from years-old, long-cancelled
  bookings).
- **API evolution / backward compatibility:** `BookingService`'s methods
  already take broad, stable parameter shapes (`List<String> seatIds`
  rather than a rigid fixed-arity signature); a real HTTP API layer on top
  would version the wire DTOs independently of this service layer, so
  internal refactors here wouldn't require a public API version bump.

---

## Repository layout

```
04-bookmyshow/
  README.md                     <- this file
  INTERVIEW_PLAYBOOK.md
  STAFF_LEVEL_DEEP_DIVE.md
  FOLLOW_UP_QUESTIONS.md
  IMPLEMENTATION_GUIDE.md
  diagrams/
    01-component-diagram.md
    02-class-diagram.md
    03-sequence-diagrams.md
  src/main/java/lld/bookmyshow/
    enums/        SeatCategory, SeatStatus, BookingStatus, PaymentStatus
    model/        City, Theatre, Screen, Seat, Movie, Show, ShowSeat,
                   SeatLock, Booking, Payment, User
    repository/   Repository, InMemoryRepository, CityRepository,
                   TheatreRepository, MovieRepository, ShowRepository,
                   BookingRepository, PaymentRepository,
                   SeatInventoryRepository
    strategy/     PricingStrategy, FixedPricingStrategy, DynamicPricingStrategy
    service/      SeatLockService, InMemorySeatLockService, PaymentGateway,
                   MockPaymentGateway, CatalogService, BookingService,
                   BookingEventListener, ConsoleBookingEventListener
    exception/    SeatUnavailableException, SeatLockExpiredException,
                   InvalidBookingStateException, PaymentFailedException,
                   ShowNotFoundException, BookingNotFoundException
    util/         IdGenerator, AdjustableClock
    demo/         BookMyShowDemo (runnable main())
  tests/lld/bookmyshow/
    TestFixtures, ShowSeatTest, InMemorySeatLockServiceTest,
    BookingServiceTest, BookingStateTransitionTest, ConcurrentBookingTest,
    PricingStrategyTest
```
