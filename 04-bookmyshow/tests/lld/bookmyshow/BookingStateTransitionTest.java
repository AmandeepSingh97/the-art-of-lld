package lld.bookmyshow;

import lld.bookmyshow.enums.BookingStatus;
import lld.bookmyshow.exception.InvalidBookingStateException;
import lld.bookmyshow.model.Booking;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins down the booking lifecycle's transition table in isolation from any service wiring. */
class BookingStateTransitionTest {

    private Booking newBooking() {
        return new Booking("b1", "user-1", "show-1", List.of("seat-1"), "lock-1",
                BigDecimal.TEN, "req-1", Instant.now());
    }

    @Test
    void validForwardTransitionsSucceed() {
        Booking booking = newBooking();
        booking.transitionTo(BookingStatus.SEATS_LOCKED);
        booking.transitionTo(BookingStatus.PAYMENT_PENDING);
        booking.transitionTo(BookingStatus.CONFIRMED);
        booking.transitionTo(BookingStatus.CANCELLED);
        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
    }

    @Test
    void cannotConfirmBeforeSeatsAreLocked() {
        Booking booking = newBooking();
        assertThrows(InvalidBookingStateException.class, () -> booking.transitionTo(BookingStatus.CONFIRMED));
    }

    @Test
    void cannotTransitionOutOfATerminalState() {
        Booking booking = newBooking();
        booking.transitionTo(BookingStatus.SEATS_LOCKED);
        booking.transitionTo(BookingStatus.PAYMENT_PENDING);
        booking.transitionTo(BookingStatus.FAILED);

        assertThrows(InvalidBookingStateException.class, () -> booking.transitionTo(BookingStatus.CONFIRMED));
        assertThrows(InvalidBookingStateException.class, () -> booking.transitionTo(BookingStatus.SEATS_LOCKED));
    }

    @Test
    void tryTransitionToReturnsFalseInsteadOfThrowingOnInvalidMove() {
        Booking booking = newBooking();
        booking.transitionTo(BookingStatus.SEATS_LOCKED);
        booking.transitionTo(BookingStatus.PAYMENT_PENDING);
        booking.transitionTo(BookingStatus.CONFIRMED);

        // Simulates a late-arriving expiry callback racing a booking that already confirmed.
        assertFalse(booking.tryTransitionTo(BookingStatus.EXPIRED));
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
    }

    @Test
    void tryTransitionToSucceedsWhenMoveIsValid() {
        Booking booking = newBooking();
        booking.transitionTo(BookingStatus.SEATS_LOCKED);
        assertTrue(booking.tryTransitionTo(BookingStatus.EXPIRED));
        assertEquals(BookingStatus.EXPIRED, booking.getStatus());
    }
}
