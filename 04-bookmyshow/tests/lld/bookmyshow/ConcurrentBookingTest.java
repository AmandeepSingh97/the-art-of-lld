package lld.bookmyshow;

import lld.bookmyshow.exception.SeatUnavailableException;
import lld.bookmyshow.model.Booking;
import lld.bookmyshow.model.Show;
import lld.bookmyshow.repository.BookingRepository;
import lld.bookmyshow.repository.PaymentRepository;
import lld.bookmyshow.repository.SeatInventoryRepository;
import lld.bookmyshow.repository.ShowRepository;
import lld.bookmyshow.service.BookingService;
import lld.bookmyshow.service.InMemorySeatLockService;
import lld.bookmyshow.service.MockPaymentGateway;
import lld.bookmyshow.service.SeatLockService;
import lld.bookmyshow.strategy.FixedPricingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The central concurrency test for the whole problem: many threads attempt
 * to book the <b>same single seat</b> on the <b>same show</b> at the same
 * time, through the full public {@code BookingService} API (lock -&gt; pay
 * -&gt; confirm), exactly as concurrent HTTP requests would. This proves the
 * system-level invariant — not just the low-level {@code ShowSeat} unit
 * invariant already covered by {@code ShowSeatTest} — because it exercises
 * the real multi-seat lock/rollback path, real idempotency keys, and a real
 * (mock) payment gateway in the mix.
 */
class ConcurrentBookingTest {

    private SeatLockService seatLockService;

    @AfterEach
    void tearDown() {
        if (seatLockService != null) {
            seatLockService.shutdown();
        }
    }

    @Test
    void exactlyOneConcurrentBookingAttemptSucceedsForTheSameSeat() throws Exception {
        ShowRepository showRepository = new ShowRepository();
        SeatInventoryRepository seatInventoryRepository = new SeatInventoryRepository();
        BookingRepository bookingRepository = new BookingRepository();
        PaymentRepository paymentRepository = new PaymentRepository();
        Clock clock = Clock.systemUTC();

        Show show = TestFixtures.createShowWithSeats(showRepository, seatInventoryRepository, 3,
                Instant.now().plus(Duration.ofHours(2)));
        String contestedSeatId = TestFixtures.seatIdByIndex(show, 1);

        seatLockService = new InMemorySeatLockService(seatInventoryRepository, clock, Duration.ofMillis(200));
        BookingService bookingService = new BookingService(seatLockService, new MockPaymentGateway(),
                new FixedPricingStrategy(), bookingRepository, paymentRepository, showRepository,
                seatInventoryRepository, clock, Duration.ofMinutes(5));

        int threadCount = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger confirmedCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        List<String> confirmedBookingIds = new CopyOnWriteArrayList<>();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                String userId = "user-" + i;
                String idempotencyKey = "req-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await();
                        Booking booking = bookingService.initiateBooking(userId, show.getId(), List.of(contestedSeatId), idempotencyKey);
                        Booking confirmed = bookingService.confirmBooking(booking.getId(), idempotencyKey);
                        confirmedBookingIds.add(confirmed.getId());
                        confirmedCount.incrementAndGet();
                    } catch (SeatUnavailableException e) {
                        rejectedCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, confirmedCount.get(),
                "Exactly one of " + threadCount + " concurrent bookings for the same seat must be confirmed");
        assertEquals(threadCount - 1, rejectedCount.get());
        assertEquals(1, confirmedBookingIds.size());

        // Final state: the seat is durably BOOKED, and exactly one booking row is CONFIRMED.
        var showSeat = seatInventoryRepository.findShowSeat(show.getId(), contestedSeatId).orElseThrow();
        assertEquals(lld.bookmyshow.enums.SeatStatus.BOOKED, showSeat.getEffectiveStatus(System.currentTimeMillis()));
    }

    @Test
    void concurrentBookingsOnDisjointSeatsAllSucceed() throws Exception {
        ShowRepository showRepository = new ShowRepository();
        SeatInventoryRepository seatInventoryRepository = new SeatInventoryRepository();
        BookingRepository bookingRepository = new BookingRepository();
        PaymentRepository paymentRepository = new PaymentRepository();
        Clock clock = Clock.systemUTC();

        int seatCount = 10;
        Show show = TestFixtures.createShowWithSeats(showRepository, seatInventoryRepository, seatCount,
                Instant.now().plus(Duration.ofHours(2)));

        seatLockService = new InMemorySeatLockService(seatInventoryRepository, clock, Duration.ofMillis(200));
        BookingService bookingService = new BookingService(seatLockService, new MockPaymentGateway(),
                new FixedPricingStrategy(), bookingRepository, paymentRepository, showRepository,
                seatInventoryRepository, clock, Duration.ofMinutes(5));

        ExecutorService pool = Executors.newFixedThreadPool(seatCount);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger confirmedCount = new AtomicInteger();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 1; i <= seatCount; i++) {
                String seatId = TestFixtures.seatIdByIndex(show, i);
                String userId = "user-" + i;
                String idempotencyKey = "req-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await();
                        Booking booking = bookingService.initiateBooking(userId, show.getId(), List.of(seatId), idempotencyKey);
                        bookingService.confirmBooking(booking.getId(), idempotencyKey);
                        confirmedCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // Disjoint seats must never contend with each other: all threads succeed independently.
        assertEquals(seatCount, confirmedCount.get());
    }
}
