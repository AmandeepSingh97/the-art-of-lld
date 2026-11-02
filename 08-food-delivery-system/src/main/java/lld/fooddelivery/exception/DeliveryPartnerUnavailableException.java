package lld.fooddelivery.exception;

/** Thrown when no delivery partner could be found/reserved for an order within the
 * configured search radius/candidate pool. */
public class DeliveryPartnerUnavailableException extends RuntimeException {
    public DeliveryPartnerUnavailableException(String orderId) {
        super("No delivery partner available for order: " + orderId);
    }
}
