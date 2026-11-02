package lld.bookmyshow.service;

import lld.bookmyshow.model.SeatLock;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Manages temporary, TTL-bounded holds on {@code ShowSeat}s during the
 * checkout flow. This is the component responsible for preventing
 * double-booking under concurrent requests.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #lockSeats} is all-or-nothing across the requested seats:
 *       either every seat is held under one {@link SeatLock}, or none are
 *       (partial holds are rolled back automatically).</li>
 *   <li>A granted lock is exclusive until it is confirmed, explicitly
 *       released, or its TTL elapses — whichever happens first.</li>
 *   <li>{@link #confirmLock} is the only way a held seat becomes
 *       permanently {@code BOOKED}; it is all-or-nothing in the same way.</li>
 * </ul>
 */
public interface SeatLockService {

    /**
     * Attempts to place a time-bounded hold on every seat in {@code seatIds}
     * for {@code ownerId}.
     *
     * @throws lld.bookmyshow.exception.SeatUnavailableException if any seat
     *         is already locked (by someone else) or booked
     */
    SeatLock lockSeats(String showId, List<String> seatIds, String ownerId, Duration ttl);

    /**
     * Converts a held lock into permanent bookings. Returns {@code false}
     * (rather than throwing) if the lock already expired or was never held,
     * so callers can decide how to react (e.g. refund and surface an error)
     * without an exception-driven control path on an expected race outcome.
     */
    boolean confirmLock(String lockId);

    /** Releases a held lock, returning its seats to AVAILABLE. No-op if the lock is unknown/already resolved. */
    void releaseLock(String lockId);

    /** Registers a callback invoked by the background reaper whenever a lock expires before being confirmed or released. */
    void onLockExpired(Consumer<SeatLock> listener);

    /** Stops the background expiry reaper. Call on application shutdown. */
    void shutdown();
}
