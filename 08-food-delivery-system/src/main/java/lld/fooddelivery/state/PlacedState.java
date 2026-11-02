package lld.fooddelivery.state;

import lld.fooddelivery.domain.Order;
import lld.fooddelivery.domain.OrderStatus;

/**
 * Order has been placed by the customer and is awaiting the restaurant's response. From here
 * the restaurant can {@link #accept} or {@link #reject}, or the customer can {@link #cancel}
 * before the restaurant acts. A background timeout (see
 * {@code lld.fooddelivery.service.OrderService}) auto-cancels orders the restaurant never
 * responds to within the configured SLA.
 */
public final class PlacedState implements OrderState {

    public static final PlacedState INSTANCE = new PlacedState();

    private PlacedState() {
    }

    @Override
    public OrderStatus status() {
        return OrderStatus.PLACED;
    }

    @Override
    public OrderState accept(Order order) {
        return AcceptedState.INSTANCE;
    }

    @Override
    public OrderState reject(Order order, String reason) {
        return RejectedState.INSTANCE;
    }

    @Override
    public OrderState cancel(Order order, String reason) {
        return CancelledState.INSTANCE;
    }
}
