package lld.slackchat.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lld.slackchat.events.ChatEvent;
import lld.slackchat.events.InMemoryEventBus;
import lld.slackchat.model.Channel;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.ChannelType;
import lld.slackchat.model.Message;
import lld.slackchat.model.MessageId;
import lld.slackchat.model.UserId;
import lld.slackchat.model.WorkspaceId;
import org.junit.jupiter.api.Test;

class DeliveryStrategyTest {

    @Test
    void fanOutOnWritePushesToEveryMemberExceptTheSenderIndividually() {
        UserId sender = UserId.generate();
        UserId memberA = UserId.generate();
        UserId memberB = UserId.generate();
        Channel channel = new Channel(
                ChannelId.generate(), WorkspaceId.generate(), "#general", ChannelType.PUBLIC,
                sender, Instant.now(), Set.of(sender, memberA, memberB));
        Message message = new Message(
                MessageId.generate(), channel.id(), sender, 1L, "hello", Set.of(), Instant.now());

        InMemoryEventBus bus = new InMemoryEventBus();
        List<ChatEvent> receivedByA = new ArrayList<>();
        List<ChatEvent> receivedByB = new ArrayList<>();
        List<ChatEvent> receivedBySender = new ArrayList<>();
        bus.subscribe("user:" + memberA, receivedByA::add);
        bus.subscribe("user:" + memberB, receivedByB::add);
        bus.subscribe("user:" + sender, receivedBySender::add);

        new FanOutOnWriteStrategy().deliver(channel, message, bus);

        assertEquals(1, receivedByA.size());
        assertEquals(1, receivedByB.size());
        assertTrue(receivedBySender.isEmpty(), "sender should not receive a delivery push for their own message");
        assertTrue(receivedByA.get(0) instanceof ChatEvent.MessageSentEvent);
    }

    @Test
    void fanOutOnReadPublishesExactlyOneChannelLevelEventRegardlessOfMemberCount() {
        UserId sender = UserId.generate();
        Set<UserId> manyMembers = new java.util.HashSet<>();
        manyMembers.add(sender);
        for (int i = 0; i < 1000; i++) {
            manyMembers.add(UserId.generate());
        }
        Channel channel = new Channel(
                ChannelId.generate(), WorkspaceId.generate(), "#announcements", ChannelType.PUBLIC,
                sender, Instant.now(), manyMembers);
        Message message = new Message(
                MessageId.generate(), channel.id(), sender, 7L, "broadcast", Set.of(), Instant.now());

        InMemoryEventBus bus = new InMemoryEventBus();
        List<ChatEvent> received = new ArrayList<>();
        bus.subscribe("channel:" + channel.id(), received::add);

        new FanOutOnReadStrategy().deliver(channel, message, bus);

        assertEquals(1, received.size(), "fan-out-on-read must publish exactly one event no matter the member count");
        ChatEvent.ChannelUpdatedEvent event = (ChatEvent.ChannelUpdatedEvent) received.get(0);
        assertEquals(7L, event.latestSequence());
    }

    @Test
    void deliveryPolicySelectsWriteStrategyForSmallChannelsAndReadStrategyForLargeOnes() {
        ChannelDeliveryPolicy policy = new ChannelDeliveryPolicy(5);
        UserId creator = UserId.generate();

        Set<UserId> smallMembers = Set.of(creator, UserId.generate(), UserId.generate());
        Channel smallChannel = new Channel(
                ChannelId.generate(), WorkspaceId.generate(), "#small", ChannelType.PUBLIC,
                creator, Instant.now(), smallMembers);

        Set<UserId> largeMembers = new java.util.HashSet<>();
        largeMembers.add(creator);
        for (int i = 0; i < 10; i++) {
            largeMembers.add(UserId.generate());
        }
        Channel largeChannel = new Channel(
                ChannelId.generate(), WorkspaceId.generate(), "#large", ChannelType.PUBLIC,
                creator, Instant.now(), largeMembers);

        assertTrue(policy.strategyFor(smallChannel) instanceof FanOutOnWriteStrategy);
        assertTrue(policy.strategyFor(largeChannel) instanceof FanOutOnReadStrategy);
        assertFalse(policy.strategyFor(largeChannel) instanceof FanOutOnWriteStrategy);
    }
}
