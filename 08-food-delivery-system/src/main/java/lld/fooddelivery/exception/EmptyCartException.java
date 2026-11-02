package lld.fooddelivery.exception;

/** Thrown when attempting to check out an empty cart. */
public class EmptyCartException extends RuntimeException {
    public EmptyCartException(String customerId) {
        super("Cart is empty for customer: " + customerId);
    }
}
