package lld.bookmyshow.enums;

/** Outcome states of a payment attempt against the {@code PaymentGateway}. */
public enum PaymentStatus {
    INITIATED,
    SUCCESS,
    FAILED,
    REFUNDED
}
