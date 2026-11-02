package lld.fooddelivery.exception;

/** Thrown when an order id does not correspond to any known order. */
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(String orderId) {
        super("No such order: " + orderId);
    }
}
