# Implementation Guide — Parking Lot

Practical, code-first guidance for actually writing this system under
interview time pressure. Complements `INTERVIEW_PLAYBOOK.md` (which is
about discussion/sequencing) with concrete coding order, gotchas, and what
to narrate while typing.

---

## Which Classes to Write First

In strict dependency order — each class below only depends on classes
above it, so you never block yourself waiting on something not yet written:

1. **Enums**: `VehicleType`, `SpotSize` (with `canFit`), `SpotStatus`,
   `TicketStatus`, `PaymentStatus`, `PaymentMethod`. Zero dependencies,
   five minutes of typing, unblocks everything else.
2. **Value objects**: `Vehicle`, `Gate`, `Money`. Immutable, no business
   logic beyond validation in the constructor.
3. **`ParkingSpot`**. The highest-value class in the whole exercise — its
   state machine is the crux of the design. Write every transition method
   (`tryReserve`, `occupy`, `releaseReservation`, `vacate`,
   `tryMarkOutOfService`, `bringBackInService`) before moving on.
4. **`Floor`, `ParkingLot`**. Thin structural holders, quick to write.
5. **Exceptions**: one base `ParkingLotException`, then the five specific
   subclasses. Write these *before* the repository/service layer that
   throws them, so you're not interrupting your own flow later.
6. **`SpotRepository` interface + `InMemorySpotRepository`**. This is
   where the concurrency design lives — write `reserveSpot` carefully, and
   say out loud why `pollFirst()` + CAS is the whole trick.
7. **`SpotAllocationStrategy` interface + `NearestAvailableSpotAllocationStrategy`**.
8. **`Ticket` + `TicketRepository` interface + `InMemoryTicketRepository`**
   (the plate-claim mechanism — `tryClaimPlate`/`releaseClaim`).
9. **`ParkingLotService.parkVehicle`** — wire steps 6-8 together with
   explicit rollback. This is the method to narrate most carefully.
10. **`PricingStrategy` + one implementation**, **`PaymentProcessor` +
    `PaymentService` + one implementation**, **`ParkingLotService.unparkVehicle`**.
11. **`EntryGateService`/`ExitGateService`** (trivial wrappers).
12. **`AdminService`** (if time remains).
13. **`ParkingLotBuilder`** (only if asked, or if you want a clean demo).

---

## Minimal Compilable Implementation Outline

If you need the absolute smallest thing that compiles and demonstrates the
design under severe time pressure (e.g. the 20-30 minute version below),
this is the skeleton:

```java
enum VehicleType { MOTORCYCLE, CAR, BUS_OR_TRUCK }
enum SpotSize {
    SMALL, MEDIUM, LARGE;
    boolean canFit(VehicleType t) {
        if (this == SMALL) return t == VehicleType.MOTORCYCLE;
        if (this == MEDIUM) return t != VehicleType.BUS_OR_TRUCK;
        return true;
    }
}
enum SpotStatus { AVAILABLE, RESERVED, OCCUPIED, OUT_OF_SERVICE }

final class Vehicle {
    final String licensePlate;
    final VehicleType type;
    Vehicle(String licensePlate, VehicleType type) {
        this.licensePlate = licensePlate;
        this.type = type;
    }
}

final class ParkingSpot {
    final String id;
    final int floorNumber;
    final SpotSize size;
    private final AtomicReference<SpotStatus> status = new AtomicReference<>(SpotStatus.AVAILABLE);
    private volatile Vehicle vehicle;

    ParkingSpot(String id, int floorNumber, SpotSize size) {
        this.id = id; this.floorNumber = floorNumber; this.size = size;
    }
    boolean tryReserve() { return status.compareAndSet(SpotStatus.AVAILABLE, SpotStatus.RESERVED); }
    void occupy(Vehicle v) {
        if (!status.compareAndSet(SpotStatus.RESERVED, SpotStatus.OCCUPIED))
            throw new IllegalStateException();
        vehicle = v;
    }
    void vacate() {
        if (!status.compareAndSet(SpotStatus.OCCUPIED, SpotStatus.AVAILABLE))
            throw new IllegalStateException();
        vehicle = null;
    }
}

final class Ticket {
    final String id; final Vehicle vehicle; final ParkingSpot spot; final Instant entryTime;
    Ticket(String id, Vehicle vehicle, ParkingSpot spot, Instant entryTime) {
        this.id = id; this.vehicle = vehicle; this.spot = spot; this.entryTime = entryTime;
    }
}

// A single class standing in for repository + allocation strategy under time pressure:
final class ParkingLot {
    private final List<ParkingSpot> spots;
    private final Map<String, Boolean> activePlates = new ConcurrentHashMap<>();
    private final AtomicLong ticketCounter = new AtomicLong();

    ParkingLot(List<ParkingSpot> spots) { this.spots = spots; }

    Ticket park(Vehicle vehicle) {
        if (activePlates.putIfAbsent(vehicle.licensePlate, true) != null)
            throw new IllegalStateException("already parked");
        for (ParkingSpot spot : spots) {
            if (spot.size.canFit(vehicle.type) && spot.tryReserve()) {
                spot.occupy(vehicle);
                return new Ticket("T" + ticketCounter.incrementAndGet(), vehicle, spot, Instant.now());
            }
        }
        activePlates.remove(vehicle.licensePlate);
        throw new IllegalStateException("no spot available");
    }

    void unpark(Ticket ticket) {
        ticket.spot.vacate();
        activePlates.remove(ticket.vehicle.licensePlate);
    }
}
```

This compiles, demonstrates the state machine, the fit rule, the atomic
plate claim, and the CAS-based allocation race — enough to carry a 20-30
minute conversation. The full repo's version differs only by splitting
these responsibilities into named interfaces/classes (Strategy, Repository,
Service) for extensibility, which you'd narrate as "the next thing I'd do
with more time is pull these responsibilities apart behind interfaces."

---

## Suggested Implementation Sequence (with time budgets for a 45-min round)

| Minutes | What to write |
|---|---|
| 0-10 | Clarify + diagram (no code yet) |
| 10-14 | Enums + `Vehicle`/`Gate`/`Money` |
| 14-22 | `ParkingSpot` full state machine |
| 22-30 | `SpotRepository`/`InMemorySpotRepository` + allocation strategy |
| 30-36 | `Ticket`/`TicketRepository` + `ParkingLotService.parkVehicle` |
| 36-41 | `unparkVehicle` + one pricing + one payment processor |
| 41-45 | Concurrency narration + edge cases (code optional, verbal is fine) |

---

## Important Methods (write these carefully, they carry the most signal)

- `ParkingSpot.tryReserve()` / `occupy()` / `vacate()` — the state machine.
  Get the CAS direction right (`compareAndSet(expected, new)`), and
  explicitly throw rather than silently no-op on an illegal transition.
- `InMemorySpotRepository.reserveSpot(floor, size)` — the `pollFirst()`
  loop. The subtlety worth narrating: polling removes the candidate from
  the index *before* confirming the CAS succeeds, so even in the (normally
  unreachable) defensive branch where CAS fails, the candidate is already
  gone from the pool — it's never put back in that branch, which is
  correct because something else already claimed it.
- `ParkingLotService.parkVehicle()` — the rollback-on-failure sequencing.
  This is the method most likely to get follow-up questions; know exactly
  what gets undone and when.
- `ParkingLotService.unparkVehicle()` — the "payment before commit"
  ordering. Say out loud why you call `paymentService.pay(...)` before
  `ticket.close(...)`.

---

## Common Coding Mistakes (and how to avoid them live)

1. **Non-atomic check-then-act on spot status**: writing
   `if (spot.getStatus() == AVAILABLE) { spot.setStatus(RESERVED); }` as
   two separate steps. This is the single most common bug in this problem
   — always reach for `compareAndSet` instead of separate read+write.
2. **Forgetting to roll back a reservation on downstream failure**: if
   `occupy()` or ticket creation fails after a spot was already reserved,
   failing to call `releaseReservation()` permanently strands that spot as
   `RESERVED` forever (nobody can ever occupy or re-allocate it). Always
   wrap the post-reservation steps in a `try/catch` with explicit cleanup.
3. **Charging before or during the close, not strictly before**: freeing
   the spot or closing the ticket *before* confirming payment succeeded
   means a failed payment still frees a spot nobody paid for — a revenue
   leak. Always: compute fee → charge → *then* close/free.
4. **Using `double` for money**: tempting under time pressure for "just a
   demo," but immediately invites a "what about floating point rounding"
   follow-up you don't want to be caught flat-footed on. Use `BigDecimal`
   (or at minimum, state clearly you'd use it and are only using `double`
   for brevity).
5. **Forgetting the duplicate-plate check**: it's easy to focus entirely on
   spot allocation and forget "can the same vehicle park twice" — this is
   usually asked explicitly as a follow-up if you don't address it
   proactively.
6. **Conflating "no spot of the right size" with "spot exists but is out
   of service"**: these are different situations an interviewer might probe
   — make sure your allocation logic naturally treats an out-of-service
   spot as unavailable (it should simply never be in the "available" index
   to begin with) rather than needing a special-case check.
7. **Making `ParkingLotService` or a repository a `static`/singleton
   out of habit**: this makes tests leak state into each other and signals
   you haven't thought about testability — always construct dependencies
   explicitly and pass them in.

---

## Edge Cases to Mention Even If Not Coded

- Garage completely full (all compatible spots occupied or out of
  service) → clean rejection, not an exception that looks like a bug.
- Same vehicle entering twice without exiting → rejected.
- Exiting with an unknown or already-closed ticket id → rejected with a
  specific error, not a null pointer exception.
- Admin trying to disable a spot that's currently occupied → rejected.
- Payment declined at exit → ticket/spot must remain exactly as they were
  before the exit attempt (not partially freed).
- Exit time somehow before entry time (clock skew, bad input) → pricing
  strategy should reject this rather than silently computing a negative
  or zero fee.

---

## How to Narrate the Code While Writing It

Keep a running commentary one level above "what" and one level below
"why the whole architecture" — narrate the *local reasoning* for whatever
you're typing right now:

> "I'm making `status` an `AtomicReference` rather than a plain field,
> because I know multiple threads will race to reserve this exact spot,
> and I want that race resolved by a single hardware-level compare-and-swap
> rather than a lock — no blocking, no risk of deadlock, and it composes
> cleanly with the repository's index-based lookup I'm about to write."

> "I'm having `occupy()` throw rather than silently fail if the spot isn't
> currently reserved, because silently failing here would hide a caller
> bug — if someone calls `occupy()` without going through `tryReserve()`
> first, I want that to be loud and immediate, not a mysteriously
> `AVAILABLE` spot later."

> "Notice the order here: I charge the payment *before* I close the ticket
> or free the spot. That's deliberate — if the charge fails, I want
> everything else to be exactly as if the exit never happened, so the
> driver can just try again."

Narrate decisions, not syntax — don't describe what a for-loop does, do
explain why you reached for `pollFirst()` instead of iterating and
checking status manually.

---

## What Can Be Safely Omitted Under Time Pressure

Safe to skip entirely, or describe verbally instead of coding:
- `ParkingLotBuilder` — construct a tiny `ParkingLot` by hand in a
  throwaway `main`/test instead.
- `FlatRatePricingStrategy` — implement only `HourlyPricingStrategy`,
  mention the interface makes a second implementation trivial.
- Multiple `PaymentProcessor` implementations — implement one, describe
  the registry pattern for the others.
- `AdminService` — describe the design (what it would guard against) even
  if you don't write the code.
- Full exception hierarchy — a single `RuntimeException` with a clear
  message is an acceptable interview-time shortcut if you say "in the real
  version I'd give each of these its own exception type for callers to
  catch selectively."

Never safe to skip:
- The `ParkingSpot` state machine itself (even a minimal version).
- Some explicit handling of the duplicate-plate case.
- At least a verbal, specific (not generic) concurrency argument for how
  two gates can't double-allocate the same spot.

---

## A Simplified Version Implementable in 20-30 Minutes

Use the "Minimal Compilable Implementation Outline" above almost verbatim:
one `ParkingSpot` class with the CAS state machine, one `ParkingLot` class
that inlines what would otherwise be `SpotRepository` +
`SpotAllocationStrategy` + `TicketRepository` into a single linear-scan
`park`/`unpark` pair, and skip pricing/payment entirely (just return the
`Ticket`, mention fee calculation as "the next thing I'd add"). This
genuinely compiles, genuinely demonstrates thread-safe allocation and the
duplicate-plate guard, and takes about 20 minutes to type while narrating.
Explicitly tell the interviewer you're collapsing several of the full
design's seams (repository, strategy) into one class for time, and name
what you'd split out first if given 15 more minutes — this demonstrates
you know the difference between "simple because it's all I have time for"
and "simple because I don't know better."
