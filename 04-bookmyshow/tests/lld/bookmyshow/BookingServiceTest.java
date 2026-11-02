package lld.bookmyshow;

import lld.bookmyshow.enums.BookingStatus;
import lld.bookmyshow.exception.InvalidBookingStateException;
import lld.bookmyshow.exception.PaymentFailedException;
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
import lld.bookmyshow.util.AdjustableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookingServiceTest {

    private ShowRepository showRepository;
    private SeatInventoryRepository seatInventoryRepository;
    private BookingRepository bookingRepository;
    private PaymentRepository paymentRepository;
    private AdjustableClock clock;
    private SeatLockService seatLockService;
    private Show show;

    @BeforeEach
    void setUp() {
        showRepository = new ShowRepository();
        seatInventoryRepository = new SeatInventoryRepository();
        bookingRepository = new BookingRepository();
        paymentRepository = new PaymentRepository();
        clock = AdjustableClock.startingAt(Instant.parse("2026-01-01T00:00:00Z"));
        show = TestFixtures.createShowWithSeats(showRepository, seatInventoryRepository, 5, Instant.parse("2026-01-01T18:00:00Z"));
        seatLockService = new InMemorySeatLockService(seatInventoryRepository, clock, Duration.ofSeconds(30));
    }

    @AfterEach
    void tearDown() {
        seatLockService.shutdown();
    }

    private BookingService newBookingService(MockPaymentGateway gateway) {
        return new BookingService(seatLockService, gateway, new FixedPricingStrategy(), bookingRepository,
                paymentRepository, showRepository, seatInventoryRepository, clock, Duration.ofMinutes(5));
    }

    @Test
    void happyPathLocksPaysAndConfirms() {
        BookingService bookingService = newBookingService(new MockPaymentGateway());
        String seat1 = TestFixtures.seatIdByIndex(show, 1);

        Booking initiated = bookingService.initiateBooking("user-1", show.getId(), List.of(seat1), "req-1");
        assertEquals(BookingStatus.SEATS_LOCKED, initiated.getStatus());

        Booking confirmed = bookingService.confirmBooking(initiated.getId(), "req-1");
        assertEquals(BookingStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(0, new BigDecimal("100.00").compareTo(confirmed.getAmount()));
    }

    @Test
    void confirmBookingIsIdempotentUnderRetry() {
        BookingService bookingService = newBookingService(new MockPaymentGateway());
        String seat1 = TestFixtures.seatIdByIndex(show, 1);

        Booking initiated = bookingService.initiateBooking("user-1", show.getId(), List.of(seat1), "req-1");
        Booking firstConfirm = bookingService.confirmBooking(initiated.getId(), "req-1");
        Booking secondConfirm = bookingService.confirmBooking(initiated.getId(), "req-1");

        assertSame(firstConfirm, secondConfirm, "retried confirm must return the same booking without re-charging");
        assertEquals(BookingStatus.CONFIRMED, secondConfirm.getStatus());
    }

    @Test
    void initiateBookingIsIdempotentUnderRetry() {
        BookingService bookingService = newBookingService(new MockPaymentGateway());
        String seat1 = TestFixtures.seatIdByIndex(show, 1);

        Booking first = bookingService.initiateBooking("user-1", show.getId(), List.of(seat1), "req-1");
        Booking second = bookingService.initiateBooking("user-1", show.getId(), List.of(seat1), "req-1");

        assertSame(first, second, "retried initiate with same idempotency key must not attempt to re-lock seats");
    }

    @Test
    void paymentFailureReleasesSeatsAndFailsBooking() {
        BookingService bookingService = newBookingService(new MockPaymentGateway(key -> true));
        String seat1 = TestFixtures.seatIdByIndex(show, 1);

        Booking initiated = bookingService.initiateBooking("user-1", show.getId(), List.of(seat1), "req-1");

        assertThrows(PaymentFailedException.class, () -> bookingService.confirmBooking(initiated.getId(), "req-1"));
        assertEquals(BookingStatus.FAILED, bookingService.getBooking(initiated.getId()).getStatus());

        // Seat must be bookable again by someone else.
        BookingService otherSession = newBookingService(new MockPaymentGateway());
        Booking retry = otherSession.initiateBooking("user-2", show.getId(), List.of(seat1), "req-2");
        assertEquals(BookingStatus.SEATS_LOCKED, retry.getStatus());
    }

    @Test
    void cancelBookingFreesSeatAndRefunds() {
        BookingService bookingService = newBookingService(new MockPaymentGateway());
        String seat1 = TestFixtures.seatIdByIndex(show, 1);

        Booking initiated = bookingService.initiateBooking("user-1", show.getId(), List.of(seat1), "req-1");
        Booking confirmed = bookingService.confirmBooking(initiated.getId(), "req-1");

        Booking cancelled = bookingService.cancelBooking(confirmed.getId());
        assertEquals(BookingStatus.CANCELLED, cancelled.getStatus());

        // Seat is free again.
        Booking rebooked = bookingService.initiateBooking("user-2", show.getId(), List.of(seat1), "req-2");
        assertEquals(BookingStatus.SEATS_LOCKED, rebooked.getStatus());
    }

    @Test
    void cancellingANonConfirmedBookingThrows() {
        BookingService bookingService = newBookingService(new MockPaymentGateway());
        String seat1 = TestFixtures.seatIdByIndex(show, 1);
        Booking initiated = bookingService.initiateBooking("user-1", show.getId(), List.of(seat1), "req-1");

        assertThrows(InvalidBookingStateException.class, () -> bookingService.cancelBooking(initiated.getId()));
    }

    @Test
    void secondUserCannotBookAnAlreadyLockedSeat() {
        BookingService bookingService = newBookingService(new MockPaymentGateway());
        String seat1 = TestFixtures.seatIdByIndex(show, 1);

        bookingService.initiateBooking("user-1", show.getId(), List.of(seat1), "req-1");

        assertThrows(lld.bookmyshow.exception.SeatUnavailableException.class,
                () -> bookingService.initiateBooking("user-2", show.getId(), List.of(seat1), "req-2"));
    }

    @Test
    void expiredHoldCannotBeConfirmed() {
        BookingService bookingService = newBookingService(new MockPaymentGateway());
        String seat1 = TestFixtures.seatIdByIndex(show, 1);

        Booking initiated = bookingService.initiateBooking("user-1", show.getId(), List.of(seat1), "req-1");
        clock.advance(Duration.ofMinutes(10)); // past the 5-minute lock TTL

        assertThrows(lld.bookmyshow.exception.SeatLockExpiredException.class,
                () -> bookingService.confirmBooking(initiated.getId(), "req-1"));
    }
}
