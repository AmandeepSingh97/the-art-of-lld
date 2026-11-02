package lld.slackchat.store;

import java.util.Optional;
import java.util.Set;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.Message;
import lld.slackchat.model.MessageId;
import lld.slackchat.model.UserId;
import lld.slackchat.pagination.MessagePage;
import lld.slackchat.pagination.PageRequest;

/**
 * Per-channel, append-only message log with keyset pagination. This is the single source of
 * truth for "what messages exist in this channel and in what order" -- everything else
 * (unread counts, delivery, notifications) is derived from it.
 */
public interface MessageStore {

    /**
     * Atomically allocates the next sequence number for {@code channelId} and appends a new
     * message. Returns the fully constructed {@link Message}.
     */
    Message append(ChannelId channelId, UserId senderId, String content, Set<UserId> mentionedUserIds);

    /** O(1) lookup by global message id, independent of channel. */
    Optional<Message> findById(MessageId messageId);

    /** Fetches one page of a channel's history per {@code request}'s cursor/direction/limit. */
    MessagePage getPage(ChannelId channelId, PageRequest request);

    /** The sequence number of the most recently appended message in {@code channelId}, or 0 if empty. */
    long latestSequence(ChannelId channelId);
}
