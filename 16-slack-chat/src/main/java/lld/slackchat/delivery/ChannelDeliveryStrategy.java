package lld.slackchat.delivery;

import lld.slackchat.events.EventBus;
import lld.slackchat.model.Channel;
import lld.slackchat.model.Message;

/**
 * Strategy interface for how a newly-sent message is fanned out to a channel's members in
 * real time. The two implementations embody the classic fan-out-on-write vs fan-out-on-read
 * trade-off (README section 14):
 *
 * <ul>
 *   <li>{@link FanOutOnWriteStrategy} -- pushes an individually addressed delivery event to
 *       every member's mailbox topic at send time. O(members) work per message, O(1) "do I
 *       have anything new" cost per member. Best for small/medium channels and DMs.</li>
 *   <li>{@link FanOutOnReadStrategy} -- publishes a single cheap pointer event to the
 *       channel's own topic and nothing per member. O(1) work per message regardless of
 *       channel size; members discover new messages by pulling (polling or fetching history).
 *       Best for very large or "broadcast" channels where most members are not actively
 *       watching and per-member pushes would be wasted/expensive work.</li>
 * </ul>
 *
 * <p>Both are genuinely useful at different scales; neither is a strictly better default,
 * which is why this is modelled as a Strategy rather than hard-coded.
 */
public interface ChannelDeliveryStrategy {
    void deliver(Channel channel, Message message, EventBus eventBus);
}
