package lld.slackchat.events;

/**
 * Observer callback invoked by the {@link EventBus} when an event is published on a topic the
 * listener is subscribed to. Stands in for a WebSocket connection / mobile push client in this
 * in-process simulation.
 *
 * <p>Implementations must be fast and non-blocking: {@link InMemoryEventBus} dispatches
 * synchronously on the publishing thread (see its class-level javadoc for why, and what a
 * production system would do differently).
 */
@FunctionalInterface
public interface EventListener {
    void onEvent(ChatEvent event);
}
