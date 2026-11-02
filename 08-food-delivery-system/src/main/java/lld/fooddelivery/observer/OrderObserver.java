package lld.fooddelivery.observer;

import lld.fooddelivery.domain.OrderEvent;

/**
 * Observer pattern contract for real-time order tracking. Each {@link lld.fooddelivery.domain.Order}
 * maintains its own list of observers (not a single global dispatcher) so that subscription is
 * scoped and cheap: a customer's tracking screen subscribes only to their own order, not to a
 * firehose of every order on the platform.
 */
public interface OrderObserver {
    void onOrderUpdated(OrderEvent event);
}
