package lld.bookmyshow.service;

import lld.bookmyshow.exception.SeatUnavailableException;
import lld.bookmyshow.model.SeatLock;
import lld.bookmyshow.model.ShowSeat;
import lld.bookmyshow.repository.SeatInventoryRepository;
import lld.bookmyshow.util.IdGenerator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * In-memory {@link SeatLockService}. See {@link ShowSeat} for the per-seat
 * CAS state machine this class orchestrates across multiple seats.
 *
 * <h2>Multi-seat atomicity</h2>
 * A booking usually spans several seats (a group booking 4 together). We
 * need "all seats lock, or none do". {@link ShowSeat#tryLock} is atomic for
 * *one* seat; composing several of those into an all-or-nothing group
 * operation needs an explicit rollback strategy, since there is no
 * multi-key CAS primitive available across independent
 * {@code AtomicReference}s. {@link #lockSeats} therefore:
 * <ol>
 *   <li>Sorts the requested seat ids before acquiring, so that two
 *       requests contending for an overlapping seat set always attempt
 *       acquisition in the same relative order — this makes the outcome
 *       deterministic and easy to reason about (not a deadlock concern,
 *       since CAS never blocks, but it avoids one request succeeding on
 *       seat A while failing on B, and a second request doing the mirror
 *       image, both wasting work in a less predictable pattern).</li>
 *   <li>Acquires seats one at a time, short-circuiting and rolling back
 *       (releasing) every seat already acquired the moment one acquisition
 *       fails.</li>
 * </ol>
 * The same rollback-on-partial-failure approach is used in
 * {@link #confirmLock}.
 *
 * <h2>Expiry</h2>
 * Expiry is enforced two ways, intentionally redundant:
 * <ul>
 *   <li><b>Lazy:</b> {@link ShowSeat#tryLock} treats any observed
 *       {@code LOCKED} state past its expiry as logically available, so
 *       correctness never depends on the background sweep having run.</li>
 *   <li><b>Active:</b> a single-threaded {@link ScheduledExecutorService}
 *       sweeps {@link #activeLocks} periodically and proactively releases
 *       anything past its TTL, so seats become available promptly even
 *       with no incoming traffic to trigger the lazy path, and so
 *       registered {@link #onLockExpired} listeners fire in bounded time
 *       (used by {@code BookingService} to mark a booking {@code EXPIRED}).</li>
 * </ul>
 */
public class InMemorySeatLockService implements SeatLockService {

    private final SeatInventoryRepository seatInventoryRepository;
    private final Clock clock;
    private final ConcurrentHashMap<String, SeatLock> activeLocks = new ConcurrentHashMap<>();
    private final List<Consumer<SeatLock>> expiryListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService reaper;

    public InMemorySeatLockService(SeatInventoryRepository seatInventoryRepository, Clock clock, Duration sweepInterval) {
        this.seatInventoryRepository = seatInventoryRepository;
        this.clock = clock;
        ThreadFactory daemonFactory = runnable -> {
            Thread t = new Thread(runnable, "seat-lock-reaper");
            t.setDaemon(true);
            return t;
        };
        this.reaper = Executors.newSingleThreadScheduledExecutor(daemonFactory);
        long intervalMillis = Math.max(sweepInterval.toMillis(), 50);
        this.reaper.scheduleAtFixedRate(this::sweepExpiredLocks, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public SeatLock lockSeats(String showId, List<String> seatIds, String ownerId, Duration ttl) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new IllegalArgumentException("seatIds must not be empty");
        }
        List<String> sortedSeatIds = seatIds.stream().sorted().toList();
        String lockId = IdGenerator.uuid();
        long nowMillis = clock.millis();
        long expiryMillis = nowMillis + ttl.toMillis();

        List<ShowSeat> acquired = new ArrayList<>(sortedSeatIds.size());
        for (String seatId : sortedSeatIds) {
            ShowSeat showSeat = seatInventoryRepository.findShowSeat(showId, seatId)
                    .orElseThrow(() -> new SeatUnavailableException(
                            "Seat " + seatId + " does not exist for show " + showId));
            boolean acquiredThisSeat = showSeat.tryLock(lockId, ownerId, expiryMillis, nowMillis);
            if (!acquiredThisSeat) {
                for (ShowSeat rollback : acquired) {
                    rollback.release(lockId);
                }
                throw new SeatUnavailableException(
                        "Seat " + seatId + " is already locked or booked for show " + showId);
            }
            acquired.add(showSeat);
        }

        SeatLock lock = new SeatLock(lockId, showId, sortedSeatIds, ownerId, Instant.ofEpochMilli(expiryMillis));
        activeLocks.put(lockId, lock);
        return lock;
    }

    @Override
    public boolean confirmLock(String lockId) {
        SeatLock lock = activeLocks.get(lockId);
        if (lock == null) {
            return false;
        }
        long nowMillis = clock.millis();
        List<ShowSeat> confirmed = new ArrayList<>(lock.getSeatIds().size());
        for (String seatId : lock.getSeatIds()) {
            ShowSeat showSeat = seatInventoryRepository.findShowSeat(lock.getShowId(), seatId).orElseThrow();
            if (!showSeat.confirm(lockId, nowMillis)) {
                // Could not confirm (expired / already resolved) — roll back any partial confirms in this group.
                for (ShowSeat rollback : confirmed) {
                    rollback.cancelBooking();
                }
                activeLocks.remove(lockId, lock);
                return false;
            }
            confirmed.add(showSeat);
        }
        activeLocks.remove(lockId, lock);
        return true;
    }

    @Override
    public void releaseLock(String lockId) {
        SeatLock lock = activeLocks.remove(lockId);
        if (lock == null) {
            return;
        }
        releaseSeats(lock);
    }

    @Override
    public void onLockExpired(Consumer<SeatLock> listener) {
        expiryListeners.add(listener);
    }

    @Override
    public void shutdown() {
        reaper.shutdown();
    }

    private void sweepExpiredLocks() {
        long nowMillis = clock.millis();
        for (SeatLock lock : activeLocks.values()) {
            if (nowMillis > lock.getExpiresAt().toEpochMilli()) {
                // Conditional remove: only this sweep's view of the lock is evicted, so a lock that was
                // concurrently confirmed/released (and possibly replaced by a brand-new lock on the same
                // seats) between our snapshot and now is left untouched.
                if (activeLocks.remove(lock.getLockId(), lock)) {
                    releaseSeats(lock);
                    for (Consumer<SeatLock> listener : expiryListeners) {
                        listener.accept(lock);
                    }
                }
            }
        }
    }

    private void releaseSeats(SeatLock lock) {
        for (String seatId : lock.getSeatIds()) {
            seatInventoryRepository.findShowSeat(lock.getShowId(), seatId).ifPresent(s -> s.release(lock.getLockId()));
        }
    }
}
