package lld.slackchat.delivery;

import java.time.Instant;
import lld.slackchat.events.ChatEvent;
import lld.slackchat.events.EventBus;
import lld.slackchat.model.Channel;
import lld.slackchat.model.Message;
import lld.slackchat.model.UserId;

/**
 * Push model: at send time, directly notifies every other channel member by publishing a
 * {@link ChatEvent.MessageSentEvent} on each member's personal {@code "user:<id>"} mailbox
 * topic. This is the in-process stand-in for "push to every open WebSocket connection /
 * mobile push token for this user" (see {@link lld.slackchat.events.InMemoryEventBus}).
 *
 * <p>Cost is O(members) per message, paid on the publishing thread. This is the right choice
 * when channels are small-to-medium (a team channel, a DM) because the constant-factor win
 * for every member -- instant, individually addressed delivery with no polling -- vastly
 * outweighs the linear write cost.
 */
public final class FanOutOnWriteStrategy implements ChannelDeliveryStrategy {

    @Override
    public void deliver(Channel channel, Message message, EventBus eventBus) {
        Instant now = Instant.now();
        for (UserId memberId : channel.memberSnapshot()) {
            if (memberId.equals(message.senderId())) {
                continue; // senders do not need a delivery notification for their own message
            }
            eventBus.publish(
                    "user:" + memberId,
                    new ChatEvent.MessageSentEvent(channel.id(), message.id(), message.senderId(), now));
        }
    }
}
