package lld.slackchat.notification;

import java.time.Instant;
import java.util.Objects;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.MessageId;
import lld.slackchat.model.UserId;

/** An in-app notification generated because {@code recipientId} was @mentioned. */
public record Notification(
        ChannelId channelId, MessageId messageId, UserId recipientId, UserId authorId, Instant createdAt) {

    public Notification {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(authorId, "authorId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
