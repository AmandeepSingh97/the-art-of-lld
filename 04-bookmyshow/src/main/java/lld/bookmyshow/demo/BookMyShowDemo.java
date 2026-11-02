package lld.bookmyshow.demo;

import lld.bookmyshow.enums.SeatCategory;
import lld.bookmyshow.exception.PaymentFailedException;
import lld.bookmyshow.exception.SeatUnavailableException;
import lld.bookmyshow.model.Booking;
import lld.bookmyshow.model.City;
import lld.bookmyshow.model.Movie;
import lld.bookmyshow.model.Screen;
import lld.bookmyshow.model.Seat;
import lld.bookmyshow.model.Show;
import lld.bookmyshow.model.Theatre;
import lld.bookmyshow.repository.BookingRepository;
import lld.bookmyshow.repository.CityRepository;
import lld.bookmyshow.repository.MovieRepository;
import lld.bookmyshow.repository.PaymentRepository;
import lld.bookmyshow.repository.SeatInventoryRepository;
import lld.bookmyshow.repository.ShowRepository;
import lld.bookmyshow.repository.TheatreRepository;
import lld.bookmyshow.service.BookingService;
import lld.bookmyshow.service.CatalogService;
import lld.bookmyshow.service.ConsoleBookingEventListener;
import lld.bookmyshow.service.InMemorySeatLockService;
import lld.bookmyshow.service.MockPaymentGateway;
import lld.bookmyshow.service.SeatLockService;
import lld.bookmyshow.strategy.FixedPricingStrategy;
import lld.bookmyshow.util.IdGenerator;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runnable, end-to-end demonstration of the BookMyShow LLD: catalog setup,
 * a happy-path booking, a payment failure, a cancellation, and — the most
 * important part for this problem — a concurrent-booking race over a
 * single seat proving only one of several simultaneous attempts wins.
 */
public final class BookMyShowDemo {

    public static void main(String[] args) throws Exception {
        // ---- Wiring (manual DI; would be a Spring/Guice context in production) ----
        CityRepository cityRepository = new CityRepository();
        TheatreRepository theatreRepository = new TheatreRepository();
        MovieRepository movieRepository = new MovieRepository();
        ShowRepository showRepository = new ShowRepository();
        SeatInventoryRepository seatInventoryRepository = new SeatInventoryRepository();
        BookingRepository bookingRepository = new BookingRepository();
        PaymentRepository paymentRepository = new PaymentRepository();

        Clock clock = Clock.systemUTC();
        SeatLockService seatLockService = new InMemorySeatLockService(seatInventoryRepository, clock, Duration.ofMillis(200));
        MockPaymentGateway paymentGateway = new MockPaymentGateway();
        FixedPricingStrategy pricingStrategy = new FixedPricingStrategy();

        BookingService bookingService = new BookingService(seatLockService, paymentGateway, pricingStrategy,
                bookingRepository, paymentRepository, showRepository, seatInventoryRepository, clock, Duration.ofMinutes(5));
        bookingService.addListener(new ConsoleBookingEventListener());

        CatalogService catalogService = new CatalogService(showRepository, seatInventoryRepository, pricingStrategy, clock);

        // ---- Catalog setup ----
        City bangalore = new City(IdGenerator.uuid(), "Bangalore");
        cityRepository.save(bangalore);

        Theatre pvr = new Theatre(IdGenerator.uuid(), "PVR Forum", bangalore);
        Screen screenOne = new Screen(IdGenerator.uuid(), "Screen 1", pvr);
        for (char row = 'A'; row <= 'C'; row++) {
            for (int number = 1; number <= 5; number++) {
                SeatCategory category = row == 'A' ? SeatCategory.PLATINUM : row == 'B' ? SeatCategory.GOLD : SeatCategory.SILVER;
                screenOne.addSeat(new Seat(IdGenerator.uuid(), screenOne.getId(), String.valueOf(row), number, category));
            }
        }
        pvr.addScreen(screenOne);
        theatreRepository.save(pvr);

        Movie movie = new Movie(IdGenerator.uuid(), "Interstellar", java.time.Duration.ofMinutes(169), "English", List.of("Sci-Fi"));
        movieRepository.save(movie);

        Show show = new Show(IdGenerator.uuid(), movie, screenOne, Instant.now().plus(Duration.ofHours(3)),
                Instant.now().plus(Duration.ofHours(6)),
                Map.of(SeatCategory.SILVER, BigDecimal.valueOf(150),
                        SeatCategory.GOLD, BigDecimal.valueOf(150),
                        SeatCategory.PLATINUM, BigDecimal.valueOf(150)));
        showRepository.save(show);
        seatInventoryRepository.initializeInventory(show);

        System.out.println("=== Seat map (first 3 seats) ===");
        catalogService.getSeatMap(show.getId()).stream().limit(3)
                .forEach(s -> System.out.println(s.label() + " " + s.category() + " status=" + s.status() + " price=" + s.price()));

        String seatA1 = findSeatId(screenOne, "A", 1);
        String seatA2 = findSeatId(screenOne, "A", 2);

        // ---- Happy path: hold -> pay -> confirm ----
        System.out.println("\n=== Happy path booking ===");
        Booking booking = bookingService.initiateBooking("user-1", show.getId(), List.of(seatA1, seatA2), "req-happy-path");
        Booking confirmed = bookingService.confirmBooking(booking.getId(), "req-happy-path");
        System.out.println("Confirmed booking status=" + confirmed.getStatus() + " amount=" + confirmed.getAmount());

        // ---- Idempotent retry of the same confirm call ----
        Booking retried = bookingService.confirmBooking(booking.getId(), "req-happy-path");
        System.out.println("Retried confirm returns same status=" + retried.getStatus() + " (no double charge, no double booking)");

        // ---- Payment failure path on a different seat ----
        System.out.println("\n=== Payment failure path ===");
        String seatA3 = findSeatId(screenOne, "A", 3);
        MockPaymentGateway failingGateway = new MockPaymentGateway(key -> key.equals("req-fail"));
        BookingService bookingServiceWithFailingGateway = new BookingService(seatLockService, failingGateway, pricingStrategy,
                bookingRepository, paymentRepository, showRepository, seatInventoryRepository, clock, Duration.ofMinutes(5));
        Booking failingBooking = bookingServiceWithFailingGateway.initiateBooking("user-2", show.getId(), List.of(seatA3), "req-fail");
        try {
            bookingServiceWithFailingGateway.confirmBooking(failingBooking.getId(), "req-fail");
        } catch (PaymentFailedException e) {
            System.out.println("Payment failed as expected: " + e.getMessage());
            System.out.println("Seat A3 is now re-bookable: " + catalogService.getSeatMap(show.getId()).stream()
                    .filter(s -> s.seatId().equals(seatA3)).findFirst().orElseThrow().status());
        }

        // ---- Cancellation / refund ----
        System.out.println("\n=== Cancellation ===");
        Booking cancelled = bookingService.cancelBooking(confirmed.getId());
        System.out.println("Booking status after cancel=" + cancelled.getStatus());

        // ---- THE critical scenario: concurrent booking attempts on the SAME seat ----
        System.out.println("\n=== Concurrent booking race on a single seat ===");
        String contestedSeat = seatA1; // just freed by the cancellation above
        int attemptCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(attemptCount);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < attemptCount; i++) {
            String userId = "concurrent-user-" + i;
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                    String idempotencyKey = "req-" + userId;
                    Booking b = bookingService.initiateBooking(userId, show.getId(), List.of(contestedSeat), idempotencyKey);
                    bookingService.confirmBooking(b.getId(), idempotencyKey);
                    successes.incrementAndGet();
                } catch (SeatUnavailableException e) {
                    failures.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        startGate.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();

        System.out.println(attemptCount + " threads raced for 1 seat -> successes=" + successes.get()
                + ", rejected=" + failures.get());
        if (successes.get() != 1) {
            throw new IllegalStateException("INVARIANT VIOLATION: expected exactly 1 success, got " + successes.get());
        }
        System.out.println("Invariant holds: exactly one booking won the seat.");

        seatLockService.shutdown();
    }

    private static String findSeatId(Screen screen, String row, int number) {
        return screen.getSeats().stream()
                .filter(s -> s.getRow().equals(row) && s.getNumber() == number)
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
