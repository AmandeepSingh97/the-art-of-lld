package lld.fooddelivery.state;

import lld.fooddelivery.domain.OrderStatus;

/**
 * Terminal state: the restaurant declined the order (e.g. kitchen is overloaded, or an item
 * went out of stock between placement and acceptance). No further transitions are legal; a
 * rejected order never accepts a delivery-partner assignment, which is exactly the invariant
 * that prevents the "reject races assignment" bug described in the concurrency notes.
 */
public final class RejectedState implements OrderState {

    public static final RejectedState INSTANCE = new RejectedState();

    private RejectedState() {
    }

    @Override
    public OrderStatus status() {
        return OrderStatus.REJECTED;
    }
}
