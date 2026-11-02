package lld.slackchat.delivery;

import java.time.Instant;
import lld.slackchat.events.ChatEvent;
import lld.slackchat.events.EventBus;
import lld.slackchat.model.Channel;
import lld.slackchat.model.Message;

/**
 * Pull model: at send time, publishes exactly one lightweight
 * {@link ChatEvent.ChannelUpdatedEvent} to the channel's own {@code "channel:<id>"} topic --
 * regardless of whether the channel has 10 or 10 million members. No per-member work happens
 * at write time.
 *
 * <p>Clients are responsible for discovering the update themselves: either by already being
 * subscribed to the (single, shared) channel topic, or by periodically pulling fresh pages
 * from the message history API (see {@code MessageStore#getPage}). This is the right choice
 * for very large or broadcast-style channels (e.g. a company-wide announcements channel),
 * where paying O(members) per message at write time would be prohibitively expensive,
 * especially since the overwhelming majority of members are typically not actively watching
 * at any given instant.
 */
public final class FanOutOnReadStrategy implements ChannelDeliveryStrategy {

    @Override
    public void deliver(Channel channel, Message message, EventBus eventBus) {
        eventBus.publish(
                "channel:" + channel.id(),
                new ChatEvent.ChannelUpdatedEvent(channel.id(), message.sequence(), Instant.now()));
    }
}
