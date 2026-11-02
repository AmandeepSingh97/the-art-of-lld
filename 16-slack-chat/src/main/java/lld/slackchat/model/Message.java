package lld.slackchat.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import lld.slackchat.exception.InvalidMessageStateException;
import lld.slackchat.exception.UnauthorizedMessageActionException;

/**
 * A single chat message posted into a {@link Channel}.
 *
 * <p>{@code sequence} is the monotonically increasing, per-channel ordering key assigned by
 * {@code MessageStore} at creation time -- it is what pagination and "unread count" math are
 * built on (see README sections 6 and 10). {@code id} is a globally unique identifier used for
 * direct lookup (edit/delete by id) independent of which channel it lives in.
 *
 * <p><b>Thread-safety:</b> a {@code Message} is mutated in place for edits/deletes (so that
 * every holder of the reference observes the update -- callers do not re-fetch). {@code
 * content} and {@code state} are {@code volatile} for visibility, and the compound
 * "check state, then mutate" operations in {@link #edit} and {@link #delete} are guarded by
 * an intrinsic lock on {@code this} so two concurrent edits (or an edit racing a delete)
 * cannot interleave. Lock granularity is per-message (not per-channel or global), so editing
 * message A never blocks editing message B, even in the same channel.
 */
public final class Message {

    private final MessageId id;
    private final ChannelId channelId;
    private final UserId senderId;
    private final long sequence;
    private final Instant createdAt;
    private final List<MessageEdit> editHistory = new CopyOnWriteArrayList<>();

    private volatile String content;
    private volatile MessageState state;
    private volatile Set<UserId> mentionedUserIds;
    private volatile Instant deletedAt;

    public Message(
            MessageId id,
            ChannelId channelId,
            UserId senderId,
            long sequence,
            String content,
            Set<UserId> mentionedUserIds,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.senderId = Objects.requireNonNull(senderId, "senderId");
        this.sequence = sequence;
        this.content = validateContent(content);
        this.mentionedUserIds = Set.copyOf(Objects.requireNonNull(mentionedUserIds, "mentionedUserIds"));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.state = MessageState.ACTIVE;
    }

    private static String validateContent(String content) {
        Objects.requireNonNull(content, "content");
        if (content.isBlank()) {
            throw new IllegalArgumentException("Message content must not be blank");
        }
        return content;
    }

    public MessageId id() {
        return id;
    }

    public ChannelId channelId() {
        return channelId;
    }

    public UserId senderId() {
        return senderId;
    }

    public long sequence() {
        return sequence;
    }

    public String content() {
        return content;
    }

    public MessageState state() {
        return state;
    }

    public Set<UserId> mentionedUserIds() {
        return mentionedUserIds;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant deletedAt() {
        return deletedAt;
    }

    /** Defensive, point-in-time copy of the audit trail. */
    public List<MessageEdit> editHistory() {
        return List.copyOf(editHistory);
    }

    /**
     * Edits the message content in place, recording the previous content as an audit entry.
     * Only the original sender may edit (this LLD does not model channel-admin override; see
     * README section 17 for the production extension point).
     *
     * @param editorId        user attempting the edit
     * @param newContent      new message body
     * @param newMentionedIds mentions recomputed from the new content
     * @throws UnauthorizedMessageActionException if {@code editorId} did not author the message
     * @throws InvalidMessageStateException       if the message has already been deleted
     */
    public synchronized void edit(UserId editorId, String newContent, Set<UserId> newMentionedIds) {
        if (!senderId.equals(editorId)) {
            throw new UnauthorizedMessageActionException(
                    "User " + editorId + " may not edit message " + id + " authored by " + senderId);
        }
        if (state == MessageState.DELETED) {
            throw new InvalidMessageStateException("Cannot edit a deleted message: " + id);
        }
        String validatedNewContent = validateContent(newContent);
        editHistory.add(new MessageEdit(this.content, Instant.now()));
        this.content = validatedNewContent;
        this.mentionedUserIds = Set.copyOf(Objects.requireNonNull(newMentionedIds, "newMentionedIds"));
        this.state = MessageState.EDITED;
    }

    /**
     * Soft-deletes the message, replacing visible content with a tombstone marker while
     * preserving the audit trail. Only the original sender may delete in this LLD.
     *
     * @throws UnauthorizedMessageActionException if {@code requesterId} did not author the message
     * @throws InvalidMessageStateException       if the message is already deleted
     */
    public synchronized void delete(UserId requesterId) {
        if (!senderId.equals(requesterId)) {
            throw new UnauthorizedMessageActionException(
                    "User " + requesterId + " may not delete message " + id + " authored by " + senderId);
        }
        if (state == MessageState.DELETED) {
            throw new InvalidMessageStateException("Message already deleted: " + id);
        }
        editHistory.add(new MessageEdit(this.content, Instant.now()));
        this.content = "[message deleted]";
        this.mentionedUserIds = Set.of();
        this.state = MessageState.DELETED;
        this.deletedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Message message)) {
            return false;
        }
        return id.equals(message.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Message{id=" + id + ", channelId=" + channelId + ", seq=" + sequence
                + ", state=" + state + "}";
    }
}
