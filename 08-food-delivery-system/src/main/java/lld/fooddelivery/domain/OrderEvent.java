package lld.fooddelivery.domain;

import java.time.Instant;

/**
 * An immutable audit record of a single order state transition, published to
 * {@link lld.fooddelivery.observer.OrderObserver}s for real-time tracking (push notifications,
 * restaurant dashboards, delivery-partner apps) and retained in {@link Order}'s history for
 * polling-based tracking ("where is my order?").
 */
public record OrderEvent(String orderId, OrderStatus from, OrderStatus to, Instant timestamp, String note) {

    public static OrderEvent of(String orderId, OrderStatus from, OrderStatus to, String note) {
        return new OrderEvent(orderId, from, to, Instant.now(), note);
    }

    @Override
    public String toString() {
        return "[" + timestamp + "] order " + orderId + ": " + from + " -> " + to
                + (note == null || note.isBlank() ? "" : " (" + note + ")");
    }
}
