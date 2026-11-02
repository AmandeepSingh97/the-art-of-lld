package lld.slackchat.events;

/**
 * Minimal publish/subscribe contract: the Observer pattern applied at the system level.
 * Topics are plain strings so the same bus can carry per-user "mailbox" topics
 * ({@code "user:<id>"}), per-channel topics ({@code "channel:<id>"}), and global topics
 * ({@code "presence"}) without needing a different mechanism for each.
 */
public interface EventBus {

    /**
     * Registers {@code listener} to receive every event subsequently published on
     * {@code topic}.
     *
     * @return a {@link Subscription} whose {@code close()} unregisters the listener
     */
    Subscription subscribe(String topic, EventListener listener);

    /** Publishes {@code event} synchronously to every listener currently subscribed to {@code topic}. */
    void publish(String topic, ChatEvent event);
}
