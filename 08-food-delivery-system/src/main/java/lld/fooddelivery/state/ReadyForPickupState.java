package lld.fooddelivery.state;

import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.OrderStatus;

/** Food is packed and waiting for the assigned delivery partner to collect it. */
public final class ReadyForPickupState implements OrderState {

    public static final ReadyForPickupState INSTANCE = new ReadyForPickupState();

    private ReadyForPickupState() {
    }

    @Override
    public OrderStatus status() {
        return OrderStatus.READY_FOR_PICKUP;
    }

    @Override
    public OrderState pickUp(Order order) {
        return PickedUpState.INSTANCE;
    }
}
