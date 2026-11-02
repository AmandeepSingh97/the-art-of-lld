# Sequence Diagrams — Key Flows

## 1. Happy-path booking: hold → pay → confirm

```mermaid
sequenceDiagram
    actor User
    participant BS as BookingService
    participant SLS as SeatLockService
    participant SS as ShowSeat (per seat)
    participant PG as PaymentGateway
    participant BR as BookingRepository

    User->>BS: initiateBooking(userId, showId, seatIds, idemKey)
    BS->>BR: findByIdempotencyKey(idemKey)
    BR-->>BS: empty
    BS->>SLS: lockSeats(showId, seatIds, userId, ttl)
    loop for each seat (sorted order)
        SLS->>SS: tryLock(lockId, userId, expiry, now)
        SS-->>SLS: true (AVAILABLE -> LOCKED)
    end
    SLS-->>BS: SeatLock(lockId, expiresAt)
    BS->>BR: save(Booking[SEATS_LOCKED])
    BS-->>User: Booking(SEATS_LOCKED)

    User->>BS: confirmBooking(bookingId, idemKey)
    BS->>BS: transitionTo(PAYMENT_PENDING)
    BS->>PG: charge(idemKey, amount)
    PG-->>BS: PaymentResult(success=true)
    BS->>SLS: confirmLock(lockId)
    loop for each seat
        SLS->>SS: confirm(lockId, now)
        SS-->>SLS: true (LOCKED -> BOOKED)
    end
    SLS-->>BS: true
    BS->>BS: transitionTo(CONFIRMED)
    BS-->>User: Booking(CONFIRMED)
```

## 2. Concurrent booking race on the same seat (the core invariant)

```mermaid
sequenceDiagram
    actor UserA
    actor UserB
    participant BS as BookingService
    participant SLS as SeatLockService
    participant SS as ShowSeat

    par UserA and UserB race for the same seat
        UserA->>BS: initiateBooking(..., seatX, idemA)
        BS->>SLS: lockSeats(show, [seatX], A, ttl)
        SLS->>SS: tryLock(lockA, A, expiryA, now)
    and
        UserB->>BS: initiateBooking(..., seatX, idemB)
        BS->>SLS: lockSeats(show, [seatX], B, ttl)
        SLS->>SS: tryLock(lockB, B, expiryB, now)
    end
    Note over SS: Internally: AtomicReference#compareAndSet<br/>Exactly one CAS wins; the other reads the<br/>post-CAS state and sees status != AVAILABLE.
    SS-->>SLS: true for the winner's CAS, false for the loser's
    SLS-->>BS: SeatLock for the winner
    SLS--xBS: SeatUnavailableException for the loser
    BS-->>UserA: Booking(SEATS_LOCKED)  %% whichever one won
    BS-->>UserB: 409 Conflict (SeatUnavailableException)
```

## 3. Seat hold expires before payment completes (TTL reaper)

```mermaid
sequenceDiagram
    participant User
    participant BS as BookingService
    participant SLS as InMemorySeatLockService
    participant Reaper as Reaper (ScheduledExecutorService)
    participant SS as ShowSeat

    User->>BS: initiateBooking(...)
    BS->>SLS: lockSeats(...)
    SLS->>SS: tryLock(...)
    SLS-->>BS: SeatLock(expiresAt=T+5m)
    Note over User: User abandons checkout; 5 minutes pass with no confirmBooking call.
    loop every sweepInterval
        Reaper->>SLS: sweepExpiredLocks()
        SLS->>SLS: now > lock.expiresAt?
    end
    SLS->>SS: release(lockId)  %% LOCKED -> AVAILABLE
    SLS->>BS: onLockExpired(lock) callback
    BS->>BS: booking.tryTransitionTo(EXPIRED)
    Note over BS: Seat is now bookable by someone else;<br/>booking record is marked EXPIRED for audit/history.
```

## 4. Payment failure (seats released, no charge sticks)

```mermaid
sequenceDiagram
    actor User
    participant BS as BookingService
    participant PG as PaymentGateway
    participant SLS as SeatLockService

    User->>BS: confirmBooking(bookingId, idemKey)
    BS->>PG: charge(idemKey, amount)
    PG-->>BS: PaymentResult(success=false, "Card declined")
    BS->>SLS: releaseLock(lockId)
    SLS->>SLS: release seats (LOCKED -> AVAILABLE)
    BS->>BS: transitionTo(FAILED)
    BS--xUser: PaymentFailedException
    Note over User: User can retry with a NEW idempotencyKey,<br/>which re-attempts initiateBooking since the old<br/>booking is terminally FAILED.
```

## 5. Cancellation and refund

```mermaid
sequenceDiagram
    actor User
    participant BS as BookingService
    participant SS as ShowSeat
    participant PG as PaymentGateway

    User->>BS: cancelBooking(bookingId)
    BS->>BS: transitionTo(CANCELLED) %% only from CONFIRMED
    loop for each seat in booking
        BS->>SS: cancelBooking()  %% BOOKED -> AVAILABLE
    end
    BS->>PG: refund(paymentId, amount)
    BS-->>User: Booking(CANCELLED)
```
