package lld.fooddelivery.state;

import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.OrderStatus;

/** Delivery partner has collected the order and is en route to the customer. */
public final class PickedUpState implements OrderState {

    public static final PickedUpState INSTANCE = new PickedUpState();

    private PickedUpState() {
    }

    @Override
    public OrderStatus status() {
        return OrderStatus.PICKED_UP;
    }

    @Override
    public OrderState deliver(Order order) {
        return DeliveredState.INSTANCE;
    }
}
