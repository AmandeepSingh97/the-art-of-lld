package lld.bookmyshow;

import lld.bookmyshow.model.Seat;
import lld.bookmyshow.model.ShowSeat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static lld.bookmyshow.enums.SeatCategory.SILVER;
import static lld.bookmyshow.enums.SeatStatus.AVAILABLE;
import static lld.bookmyshow.enums.SeatStatus.BOOKED;
import static lld.bookmyshow.enums.SeatStatus.LOCKED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the seat-level CAS state machine in isolation, without any
 * service-layer orchestration. These pin down the exact transition rules
 * that {@code InMemorySeatLockService} relies on.
 */
class ShowSeatTest {

    private ShowSeat showSeat;

    @BeforeEach
    void setUp() {
        Seat seat = new Seat("seat-1", "screen-1", "A", 1, SILVER);
        showSeat = new ShowSeat("show-1", seat);
    }

    @Test
    void tryLockSucceedsOnAvailableSeat() {
        boolean acquired = showSeat.tryLock("lock-1", "user-1", Long.MAX_VALUE, 0L);
        assertTrue(acquired);
        assertEquals(LOCKED, showSeat.getEffectiveStatus(1L));
    }

    @Test
    void secondTryLockFailsWhileFirstLockIsHeldAndUnexpired() {
        assertTrue(showSeat.tryLock("lock-1", "user-1", 10_000L, 0L));
        assertFalse(showSeat.tryLock("lock-2", "user-2", 10_000L, 1L));
    }

    @Test
    void tryLockSucceedsAfterPriorLockExpires() {
        assertTrue(showSeat.tryLock("lock-1", "user-1", /*expiry*/ 100L, /*now*/ 0L));
        // now (500) is past expiry (100) -> lazily reclaimed as available.
        assertTrue(showSeat.tryLock("lock-2", "user-2", 10_000L, 500L));
    }

    @Test
    void confirmTransitionsLockedToBooked() {
        showSeat.tryLock("lock-1", "user-1", 10_000L, 0L);
        assertTrue(showSeat.confirm("lock-1", 1L));
        assertEquals(BOOKED, showSeat.getEffectiveStatus(2L));
    }

    @Test
    void confirmFailsWithWrongLockId() {
        showSeat.tryLock("lock-1", "user-1", 10_000L, 0L);
        assertFalse(showSeat.confirm("some-other-lock", 1L));
        assertEquals(LOCKED, showSeat.getEffectiveStatus(2L));
    }

    @Test
    void confirmFailsAfterExpiry() {
        showSeat.tryLock("lock-1", "user-1", 100L, 0L);
        assertFalse(showSeat.confirm("lock-1", 500L));
    }

    @Test
    void releaseReturnsSeatToAvailableAndAllowsNewLock() {
        showSeat.tryLock("lock-1", "user-1", 10_000L, 0L);
        assertTrue(showSeat.release("lock-1"));
        assertEquals(AVAILABLE, showSeat.getEffectiveStatus(1L));
        assertTrue(showSeat.tryLock("lock-2", "user-2", 10_000L, 1L));
    }

    @Test
    void releaseWithStaleLockIdIsNoOp() {
        showSeat.tryLock("lock-1", "user-1", 10_000L, 0L);
        showSeat.release("lock-1");
        showSeat.tryLock("lock-2", "user-2", 10_000L, 1L);
        // A stale release referencing the first (already-released) lock must not disturb the second holder.
        assertFalse(showSeat.release("lock-1"));
        assertEquals(LOCKED, showSeat.getEffectiveStatus(2L));
    }

    @Test
    void cancelBookingReturnsBookedSeatToAvailable() {
        showSeat.tryLock("lock-1", "user-1", 10_000L, 0L);
        showSeat.confirm("lock-1", 1L);
        assertTrue(showSeat.cancelBooking());
        assertEquals(AVAILABLE, showSeat.getEffectiveStatus(2L));
    }

    @Test
    void cancelBookingFailsWhenSeatIsNotBooked() {
        assertFalse(showSeat.cancelBooking());
        showSeat.tryLock("lock-1", "user-1", 10_000L, 0L);
        assertFalse(showSeat.cancelBooking()); // LOCKED, not BOOKED
    }

    /**
     * THE critical concurrency invariant for this entire problem: when many
     * threads race to lock the same seat at the same instant, exactly one
     * must win. Uses a {@link CountDownLatch} to maximize the chance all
     * threads attempt {@code tryLock} at essentially the same time, which is
     * the worst case for a naive (non-atomic) implementation.
     */
    @Test
    void exactlyOneThreadWinsWhenManyThreadsRaceForTheSameSeat() throws Exception {
        int threadCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();

        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < threadCount; i++) {
                String lockId = "lock-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (showSeat.tryLock(lockId, "user-" + lockId, Long.MAX_VALUE, 0L)) {
                        successCount.incrementAndGet();
                    }
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(1, successCount.get(), "Exactly one of " + threadCount + " concurrent lock attempts must succeed");
        assertEquals(LOCKED, showSeat.getEffectiveStatus(1L));
    }
}
