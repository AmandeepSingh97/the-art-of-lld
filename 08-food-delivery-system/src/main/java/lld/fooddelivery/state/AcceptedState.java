package lld.fooddelivery.state;

import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.OrderStatus;

/**
 * Restaurant has accepted the order. The kitchen has not necessarily started cooking yet, but
 * a delivery partner search may already begin in parallel (hence
 * {@link #allowsDeliveryPartnerAssignment()} returns {@code true} here). Customer-initiated
 * cancellation is still allowed up to this point; once {@link #startPreparing} fires, the
 * kitchen has committed resources (ingredients, cook time) and cancellation is no longer
 * self-service.
 */
public final class AcceptedState implements OrderState {

    public static final AcceptedState INSTANCE = new AcceptedState();

    private AcceptedState() {
    }

    @Override
    public OrderStatus status() {
        return OrderStatus.ACCEPTED;
    }

    @Override
    public OrderState startPreparing(Order order) {
        return PreparingState.INSTANCE;
    }

    @Override
    public OrderState cancel(Order order, String reason) {
        return CancelledState.INSTANCE;
    }

    @Override
    public boolean allowsDeliveryPartnerAssignment() {
        return true;
    }
}
