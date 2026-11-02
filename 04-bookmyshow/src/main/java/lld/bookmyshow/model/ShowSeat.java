package lld.bookmyshow.model;

import lld.bookmyshow.enums.SeatCategory;
import lld.bookmyshow.enums.SeatStatus;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable, per-show availability record for exactly one physical seat.
 * This is the single most important class in the system: it is the unit of
 * concurrency control that prevents two users from booking the same seat on
 * the same show at the same time.
 *
 * <h2>Why lock-free CAS instead of {@code synchronized}</h2>
 * A naive implementation would guard a {@code SeatStatus} field with a
 * {@code synchronized} method. That works, but it serializes <em>all</em>
 * readers and writers of a seat behind a monitor, and makes it easy to
 * accidentally hold the lock across I/O (e.g. a careless call to a payment
 * gateway) which would stall every other thread contending for that seat.
 *
 * <p>Instead, all mutable fields that must change together atomically
 * (status, lock owner, lock id, expiry) are bundled into one immutable
 * {@link LockState} value, held in a single {@link AtomicReference}. Every
 * transition is a classic optimistic-concurrency "read current -&gt; compute
 * next -&gt; compareAndSet, retry on failure" loop. This is lock-free
 * (wait-free under the single contended CAS, in practice no retries beyond
 * a handful even under heavy contention because the critical section is a
 * few nanoseconds of pointer compare), never blocks a thread, and cannot
 * deadlock because there is only ever one atomic reference involved — no
 * multi-lock ordering problem exists.
 *
 * <p>Expiry is handled lazily as well as by a background reaper (see
 * {@code InMemorySeatLockService}): any thread that observes a
 * {@code LOCKED} state past its {@code expiryEpochMillis} treats it as
 * logically {@code AVAILABLE} for the purposes of deciding whether a new
 * lock can be granted, which makes the system self-healing even if the
 * background sweep is delayed or temporarily not running.
 */
public final class ShowSeat {

    /** Immutable snapshot of a seat's booking state at one instant. */
    public record LockState(SeatStatus status, String lockId, String ownerId, long expiryEpochMillis) {
        static LockState available() {
            return new LockState(SeatStatus.AVAILABLE, null, null, 0L);
        }
    }

    private final String showId;
    private final Seat seat;
    private final AtomicReference<LockState> stateRef = new AtomicReference<>(LockState.available());

    public ShowSeat(String showId, Seat seat) {
        this.showId = Objects.requireNonNull(showId, "showId");
        this.seat = Objects.requireNonNull(seat, "seat");
    }

    public String getShowId() {
        return showId;
    }

    public Seat getSeat() {
        return seat;
    }

    public SeatCategory getCategory() {
        return seat.getCategory();
    }

    /** Best-effort current status, treating an expired lock as available. */
    public SeatStatus getEffectiveStatus(long nowEpochMillis) {
        return reclaimIfExpired(stateRef.get(), nowEpochMillis).status();
    }

    /**
     * Attempts to place a hold on this seat for {@code ownerId}, identified
     * by the caller-supplied unique {@code lockId}, valid until
     * {@code expiryEpochMillis}.
     *
     * @return {@code true} if the seat transitioned AVAILABLE -&gt; LOCKED
     *         under this call; {@code false} if it was already
     *         LOCKED (unexpired) or BOOKED, meaning the caller lost the race.
     */
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

    /**
     * Confirms a previously acquired lock, transitioning LOCKED -&gt; BOOKED.
     * Only succeeds if {@code lockId} matches the current holder and the
     * lock has not expired, which makes booking confirmation idempotent
     * from the seat's point of view: a retried confirm call for the same
     * {@code lockId} after it already succeeded will observe status BOOKED
     * (not LOCKED) and simply return {@code false} without side effects —
     * the caller (BookingService) handles true idempotency via the booking
     * id / idempotency key, this is a defense-in-depth guard at the seat level.
     */
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

    /**
     * Releases a held lock back to AVAILABLE. Used both for explicit
     * release (user abandons checkout, payment fails) and by the background
     * expiry reaper. Matching on {@code lockId} ensures a stale reaper task
     * can never release a seat that has since been re-locked (or booked)
     * under a different lock id.
     */
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

    /** Transitions BOOKED -&gt; AVAILABLE, used for cancellations/refunds. */
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

    public LockState snapshot() {
        return stateRef.get();
    }

    private static LockState reclaimIfExpired(LockState state, long nowEpochMillis) {
        if (state.status() == SeatStatus.LOCKED && nowEpochMillis > state.expiryEpochMillis()) {
            return LockState.available();
        }
        return state;
    }
}
