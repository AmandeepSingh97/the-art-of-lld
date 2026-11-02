package lld.slackchat.events;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process, synchronous {@link EventBus} implementation.
 *
 * <p><b>This is the explicit simulation boundary for real-time delivery.</b> A production
 * Slack-like system would not dispatch events via direct in-JVM method calls; it would push
 * them over persistent WebSocket connections, typically fanned out through a message broker
 * (Kafka/Redis Pub-Sub/Pulsar) to whichever gateway node holds the recipient's socket, across
 * a fleet of machines, with at-least-once delivery and client-side de-duplication. This class
 * reproduces the *observer pattern and delivery semantics* (who is notified, in what order,
 * single-member vs broadcast) faithfully, but intentionally does not reproduce the network
 * transport, which is out of scope for this LLD. See README section 17.
 *
 * <p><b>Thread-safety:</b> {@code topics} is a {@link ConcurrentHashMap} keyed by topic name,
 * each value a {@link CopyOnWriteArrayList} of listeners. Subscribe/unsubscribe (rare) copy
 * the backing array; publish (frequent -- once per message send, per edit, per presence
 * change) iterates a snapshot with zero locking. This is the same read-mostly/write-rare
 * trade-off used throughout the codebase for membership and listener registries.
 *
 * <p>Dispatch is synchronous and on the calling thread: a slow or throwing listener can delay
 * or fail the publishing call. We accept that trade-off here for determinism in tests and
 * interview-time simplicity; README section 13 discusses isolating listener failures and
 * moving dispatch to a worker pool as a direct extension.
 */
public final class InMemoryEventBus implements EventBus {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<EventListener>> topics =
            new ConcurrentHashMap<>();

    @Override
    public Subscription subscribe(String topic, EventListener listener) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(listener, "listener");
        CopyOnWriteArrayList<EventListener> listeners =
                topics.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void publish(String topic, ChatEvent event) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(event, "event");
        List<EventListener> listeners = topics.get(topic);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (EventListener listener : listeners) {
            // A misbehaving listener must not prevent other listeners (or the sender) from
            // completing. In production this isolation is normally achieved by dispatching
            // each listener on its own worker/queue instead of catching exceptions inline.
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                System.err.println(
                        "EventBus listener on topic '" + topic + "' threw: " + e.getMessage());
            }
        }
    }
}
