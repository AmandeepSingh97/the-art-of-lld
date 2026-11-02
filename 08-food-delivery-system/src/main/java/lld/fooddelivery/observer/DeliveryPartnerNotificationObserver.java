package lld.fooddelivery.observer;

import lld.fooddelivery.domain.OrderEvent;

/** Simulates notifying the assigned delivery partner's rider app of order updates. */
public final class DeliveryPartnerNotificationObserver implements OrderObserver {

    @Override
    public void onOrderUpdated(OrderEvent event) {
        System.out.println("[rider-app] " + event);
    }
}
