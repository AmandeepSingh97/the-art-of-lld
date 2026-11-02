package lld.slackchat.delivery;

import lld.slackchat.model.Channel;

/**
 * Chooses which {@link ChannelDeliveryStrategy} a channel should use, based on its member
 * count. This is the adaptive policy layer referenced in README section 14: rather than
 * forcing every caller to pick a strategy, the system defaults to fan-out-on-write for
 * "normal sized" channels and automatically switches to fan-out-on-read once a channel grows
 * past {@code largeChannelThreshold} members.
 *
 * <p>The threshold is a simple, interview-appropriate heuristic, not a production tuning
 * value -- a real system would also weigh member *activity* (fraction currently connected),
 * not just raw count. See README section 17.
 */
public final class ChannelDeliveryPolicy {

    private final ChannelDeliveryStrategy fanOutOnWrite;
    private final ChannelDeliveryStrategy fanOutOnRead;
    private final int largeChannelThreshold;

    public ChannelDeliveryPolicy(int largeChannelThreshold) {
        if (largeChannelThreshold <= 0) {
            throw new IllegalArgumentException(
                    "largeChannelThreshold must be positive, got " + largeChannelThreshold);
        }
        this.largeChannelThreshold = largeChannelThreshold;
        this.fanOutOnWrite = new FanOutOnWriteStrategy();
        this.fanOutOnRead = new FanOutOnReadStrategy();
    }

    public ChannelDeliveryStrategy strategyFor(Channel channel) {
        return channel.memberCount() > largeChannelThreshold ? fanOutOnRead : fanOutOnWrite;
    }
}
