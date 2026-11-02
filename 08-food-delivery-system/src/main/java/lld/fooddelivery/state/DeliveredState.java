package lld.fooddelivery.state;

import lld.fooddelivery.domain.OrderStatus;

/** Terminal (happy-path) state: the order has been handed to the customer. */
public final class DeliveredState implements OrderState {

    public static final DeliveredState INSTANCE = new DeliveredState();

    private DeliveredState() {
    }

    @Override
    public OrderStatus status() {
        return OrderStatus.DELIVERED;
    }
}
