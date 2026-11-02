package lld.bookmyshow.enums;

/**
 * Lifecycle of a {@code Booking} aggregate. Transitions are validated by
 * {@code Booking#transitionTo} so an invalid jump (e.g. CANCELLED -&gt;
 * CONFIRMED) throws {@code InvalidBookingStateException} instead of silently
 * corrupting state.
 *
 * <pre>
 *  CREATED -&gt; SEATS_LOCKED -&gt; PAYMENT_PENDING -&gt; CONFIRMED -&gt; CANCELLED
 *                    |               |
 *                    v               v
 *                 EXPIRED          FAILED
 * </pre>
 */
public enum BookingStatus {
    CREATED,
    SEATS_LOCKED,
    PAYMENT_PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    FAILED
}
