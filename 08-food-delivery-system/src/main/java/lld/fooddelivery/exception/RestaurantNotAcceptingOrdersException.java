package lld.fooddelivery.exception;

/** Thrown when an order is placed against a restaurant that is currently closed/paused. */
public class RestaurantNotAcceptingOrdersException extends RuntimeException {
    public RestaurantNotAcceptingOrdersException(String restaurantId) {
        super("Restaurant " + restaurantId + " is not currently accepting orders");
    }
}
