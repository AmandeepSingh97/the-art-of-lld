package lld.slackchat.events;

/** Handle returned by {@link EventBus#subscribe} allowing a listener to unsubscribe. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
