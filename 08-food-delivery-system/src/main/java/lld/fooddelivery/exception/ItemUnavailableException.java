package lld.fooddelivery.exception;

/** Thrown when a cart-checkout re-validation finds that a menu item became unavailable
 * (e.g. the kitchen 86'd it) between the time it was added to the cart and checkout time. */
public class ItemUnavailableException extends RuntimeException {
    public ItemUnavailableException(String itemId, String itemName) {
        super("Item '" + itemName + "' (" + itemId + ") is no longer available");
    }
}
