package lld.fooddelivery.observer;

import lld.fooddelivery.domain.OrderEvent;

/** Simulates updating the restaurant's live order dashboard. */
public final class RestaurantDashboardObserver implements OrderObserver {

    @Override
    public void onOrderUpdated(OrderEvent event) {
        System.out.println("[restaurant-dashboard] " + event);
    }
}
