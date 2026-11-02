package lld.bookmyshow;

import lld.bookmyshow.exception.SeatUnavailableException;
import lld.bookmyshow.model.SeatLock;
import lld.bookmyshow.model.Show;
import lld.bookmyshow.repository.SeatInventoryRepository;
import lld.bookmyshow.repository.ShowRepository;
import lld.bookmyshow.service.InMemorySeatLockService;
import lld.bookmyshow.service.SeatLockService;
import lld.bookmyshow.util.AdjustableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static lld.bookmyshow.enums.SeatStatus.AVAILABLE;
import static lld.bookmyshow.enums.SeatStatus.BOOKED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemorySeatLockServiceTest {

    private ShowRepository showRepository;
    private SeatInventoryRepository seatInventoryRepository;
    private AdjustableClock clock;
    private InMemorySeatLockService seatLockService;
    private Show show;

    @BeforeEach
    void setUp() {
        showRepository = new ShowRepository();
        seatInventoryRepository = new SeatInventoryRepository();
        clock = AdjustableClock.startingAt(Instant.parse("2026-01-01T00:00:00Z"));
        show = TestFixtures.createShowWithSeats(showRepository, seatInventoryRepository, 5, Instant.parse("2026-01-01T18:00:00Z"));
        // Long sweep interval: most tests drive expiry explicitly via tryLock's lazy-reclaim path
        // or by waiting past the interval in the one test that needs the active reaper.
        seatLockService = new InMemorySeatLockService(seatInventoryRepository, clock, Duration.ofMillis(50));
    }

    @AfterEach
    void tearDown() {
        seatLockService.shutdown();
    }

    @Test
    void lockSeatsGrantsHoldOverAllRequestedSeats() {
        String seat1 = TestFixtures.seatIdByIndex(show, 1);
        String seat2 = TestFixtures.seatIdByIndex(show, 2);

        SeatLock lock = seatLockService.lockSeats(show.getId(), List.of(seat1, seat2), "user-1", Duration.ofMinutes(5));

        assertEquals(2, lock.getSeatIds().size());
        assertEquals(lld.bookmyshow.enums.SeatStatus.LOCKED,
                seatInventoryRepository.findShowSeat(show.getId(), seat1).orElseThrow().getEffectiveStatus(clock.millis()));
    }

    @Test
    void lockSeatsIsAllOrNothingAndRollsBackOnPartialConflict() {
        String seat1 = TestFixtures.seatIdByIndex(show, 1);
        String seat2 = TestFixtures.seatIdByIndex(show, 2);

        // Someone else already holds seat2.
        seatLockService.lockSeats(show.getId(), List.of(seat2), "other-user", Duration.ofMinutes(5));

        assertThrows(SeatUnavailableException.class,
                () -> seatLockService.lockSeats(show.getId(), List.of(seat1, seat2), "user-1", Duration.ofMinutes(5)));

        // seat1 must have been rolled back to AVAILABLE, not left dangling as LOCKED.
        assertEquals(AVAILABLE,
                seatInventoryRepository.findShowSeat(show.getId(), seat1).orElseThrow().getEffectiveStatus(clock.millis()));
    }

    @Test
    void confirmLockTransitionsAllSeatsToBooked() {
        String seat1 = TestFixtures.seatIdByIndex(show, 1);
        SeatLock lock = seatLockService.lockSeats(show.getId(), List.of(seat1), "user-1", Duration.ofMinutes(5));

        assertTrue(seatLockService.confirmLock(lock.getLockId()));
        assertEquals(BOOKED, seatInventoryRepository.findShowSeat(show.getId(), seat1).orElseThrow().getEffectiveStatus(clock.millis()));
    }

    @Test
    void confirmLockReturnsFalseForUnknownLockId() {
        assertFalse(seatLockService.confirmLock("does-not-exist"));
    }

    @Test
    void releaseLockFreesSeatsForNewLocking() {
        String seat1 = TestFixtures.seatIdByIndex(show, 1);
        SeatLock lock = seatLockService.lockSeats(show.getId(), List.of(seat1), "user-1", Duration.ofMinutes(5));

        seatLockService.releaseLock(lock.getLockId());

        SeatLock secondLock = seatLockService.lockSeats(show.getId(), List.of(seat1), "user-2", Duration.ofMinutes(5));
        assertEquals(1, secondLock.getSeatIds().size());
    }

    @Test
    void expiredLockIsLazilyReclaimedWithoutWaitingForReaper() {
        String seat1 = TestFixtures.seatIdByIndex(show, 1);
        seatLockService.lockSeats(show.getId(), List.of(seat1), "user-1", Duration.ofMillis(10));

        clock.advance(Duration.ofSeconds(1)); // well past the 10ms TTL

        // No sleep, no reaper tick needed: tryLock's lazy-reclaim sees the expired lock immediately.
        SeatLock newLock = seatLockService.lockSeats(show.getId(), List.of(seat1), "user-2", Duration.ofMinutes(5));
        assertEquals("user-2", newLock.getOwnerId());
    }

    @Test
    void backgroundReaperActivelyReleasesExpiredLocksAndFiresListener() throws InterruptedException {
        String seat1 = TestFixtures.seatIdByIndex(show, 1);
        AtomicReference<SeatLock> expiredLock = new AtomicReference<>();
        CountDownLatch expiredLatch = new CountDownLatch(1);
        seatLockService.onLockExpired(lock -> {
            expiredLock.set(lock);
            expiredLatch.countDown();
        });

        seatLockService.lockSeats(show.getId(), List.of(seat1), "user-1", Duration.ofMillis(10));
        clock.advance(Duration.ofSeconds(1));

        // The reaper runs on wall-clock time (every 50ms, real time), independent of our adjustable
        // logical clock, so give it a little real wall-clock time to tick at least once.
        assertTrue(expiredLatch.await(2, TimeUnit.SECONDS), "expiry listener should have fired");
        assertEquals(seat1, expiredLock.get().getSeatIds().get(0));
        assertEquals(AVAILABLE,
                seatInventoryRepository.findShowSeat(show.getId(), seat1).orElseThrow().getEffectiveStatus(clock.millis()));
    }
}
