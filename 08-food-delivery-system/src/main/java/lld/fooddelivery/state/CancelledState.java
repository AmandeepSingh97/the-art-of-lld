package lld.fooddelivery.state;

import lld.fooddelivery.domain.OrderStatus;

/**
 * Terminal state: the order was cancelled, either by the customer (only legal from
 * {@code PLACED}/{@code ACCEPTED}, before the kitchen started cooking) or by the system (e.g.
 * acceptance-timeout auto-cancellation). Any in-flight delivery-partner reservation must be
 * released back to the pool by the service layer when this transition occurs.
 */
public final class CancelledState implements OrderState {

    public static final CancelledState INSTANCE = new CancelledState();

    private CancelledState() {
    }

    @Override
    public OrderStatus status() {
        return OrderStatus.CANCELLED;
    }
}
