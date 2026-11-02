package lld.fooddelivery.observer;

import lld.fooddelivery.domain.OrderEvent;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures the full event history for an order in-memory so a polling-based tracking API
 * (e.g. "GET /orders/{id}/tracking") can return the latest known state without needing a
 * push/WebSocket channel. Backed by {@link CopyOnWriteArrayList}: reads (polling) vastly
 * outnumber writes (one event per state transition), which is exactly the access pattern
 * copy-on-write collections are optimised for.
 */
public final class OrderTrackingObserver implements OrderObserver {

    private final List<OrderEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onOrderUpdated(OrderEvent event) {
        events.add(event);
    }

    public List<OrderEvent> timeline() {
        return Collections.unmodifiableList(events);
    }

    public OrderEvent latest() {
        if (events.isEmpty()) {
            return null;
        }
        return events.get(events.size() - 1);
    }
}
