package lld.fooddelivery.observer;

import lld.fooddelivery.domain.OrderEvent;

/**
 * Simulates pushing a tracking update to the customer's app (in production: a push
 * notification / WebSocket frame to the customer's tracking screen).
 */
public final class CustomerNotificationObserver implements OrderObserver {

    @Override
    public void onOrderUpdated(OrderEvent event) {
        System.out.println("[customer-push] " + event);
    }
}
