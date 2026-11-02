package lld.slackchat.events;

import java.time.Instant;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.MessageId;
import lld.slackchat.model.PresenceStatus;
import lld.slackchat.model.UserId;

/**
 * Sealed hierarchy of real-time events published on the {@link EventBus}.
 *
 * <p>This is the explicit <b>simulation boundary</b> called out in the problem brief: in a
 * real system these events would be delivered to browser/mobile clients over a persistent
 * WebSocket connection (or fanned out through a broker such as Kafka/Redis Pub-Sub to
 * WebSocket gateway nodes across a fleet). Here, {@link EventListener}s standing in for those
 * client connections are plain in-process objects subscribed on an {@link InMemoryEventBus}.
 * The event *shapes* and *delivery semantics* (who gets notified, in what order, fan-out vs
 * fan-in) are what this LLD models faithfully; the network transport is out of scope and is
 * discussed only in README section 17 (Production Evolution).
 */
public sealed interface ChatEvent {

    Instant occurredAt();

    /** A new message was appended to a channel. Used by the fan-out-on-write strategy to push
     * a full copy to each member's mailbox topic. */
    record MessageSentEvent(ChannelId channelId, MessageId messageId, UserId senderId, Instant occurredAt)
            implements ChatEvent {}

    /** An existing message was edited. */
    record MessageEditedEvent(ChannelId channelId, MessageId messageId, UserId editorId, Instant occurredAt)
            implements ChatEvent {}

    /** An existing message was (soft) deleted. */
    record MessageDeletedEvent(ChannelId channelId, MessageId messageId, UserId requesterId, Instant occurredAt)
            implements ChatEvent {}

    /** A user was @mentioned in a message; consumed by {@code NotificationService}. */
    record MentionEvent(ChannelId channelId, MessageId messageId, UserId mentionedUserId, UserId authorId,
                         Instant occurredAt) implements ChatEvent {}

    /** A user's presence status changed. */
    record PresenceChangedEvent(UserId userId, PresenceStatus status, Instant occurredAt) implements ChatEvent {}

    /**
     * Lightweight "something changed in this channel, go check" pointer used by the
     * fan-out-on-read strategy: a single event is published to one channel-level topic
     * instead of one event per member, and interested clients pull the actual message via
     * the history API. See README section 14 for the fan-out trade-off discussion.
     */
    record ChannelUpdatedEvent(ChannelId channelId, long latestSequence, Instant occurredAt) implements ChatEvent {}
}
