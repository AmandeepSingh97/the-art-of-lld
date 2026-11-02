package lld.bookmyshow.enums;

/**
 * Lifecycle states of a single seat <b>for one specific show</b>.
 *
 * <p>This is deliberately distinct from the physical {@code Seat} entity:
 * the same physical seat is {@code AVAILABLE} for a 6 PM show and
 * simultaneously {@code BOOKED} for a 9 PM show. State is tracked per
 * {@code (showId, seatId)} pair via {@code ShowSeat}.
 *
 * <p>Valid transitions (enforced by {@code ShowSeat#compareAndTransition}):
 * <pre>
 *   AVAILABLE --lock()--&gt; LOCKED --confirm()--&gt; BOOKED
 *   LOCKED --release()/expire()--&gt; AVAILABLE
 *   BOOKED --cancel()--&gt; AVAILABLE
 * </pre>
 * Any other transition is rejected, which is what prevents double-booking.
 */
public enum SeatStatus {
    AVAILABLE,
    LOCKED,
    BOOKED
}
