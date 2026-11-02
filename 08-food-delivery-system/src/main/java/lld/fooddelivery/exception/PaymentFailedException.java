package lld.fooddelivery.exception;

/** Thrown when a payment attempt fails (declined, gateway error, timeout). */
public class PaymentFailedException extends RuntimeException {
    public PaymentFailedException(String orderId, String reason) {
        super("Payment failed for order " + orderId + ": " + reason);
    }
}
