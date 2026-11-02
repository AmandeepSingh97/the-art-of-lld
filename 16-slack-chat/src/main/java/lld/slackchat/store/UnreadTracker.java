package lld.slackchat.store;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.UserId;

/**
 * Tracks, per (channel, user) pair, the sequence number of the last message that user has
 * read, and derives unread counts from it. Backs both read-receipts and unread badges.
 *
 * <p><b>Thread-safety:</b> a nested {@code ConcurrentHashMap<ChannelId,
 * ConcurrentHashMap<UserId, AtomicLong>>}. {@link #markRead} must be monotonic: a client that
 * is slow to report "read up to seq 40" must never regress a read position already advanced
 * to seq 50 by a newer client action (e.g. two devices, or an out-of-order network delivery).
 * We enforce this with {@link AtomicLong#updateAndGet} taking {@code Math::max}, which is a
 * single lock-free CAS loop -- strictly stronger than "synchronized setter" because it is
 * correct even if two threads call {@code markRead} concurrently with different values.
 */
public final class UnreadTracker {

    private final Map<ChannelId, ConcurrentHashMap<UserId, AtomicLong>> lastReadSequence =
            new ConcurrentHashMap<>();

    /** Advances the caller's read position for {@code channelId} to {@code uptoSequence}, never regressing it. */
    public void markRead(ChannelId channelId, UserId userId, long uptoSequence) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(userId, "userId");
        ConcurrentHashMap<UserId, AtomicLong> channelReads =
                lastReadSequence.computeIfAbsent(channelId, c -> new ConcurrentHashMap<>());
        AtomicLong position = channelReads.computeIfAbsent(userId, u -> new AtomicLong(0L));
        position.updateAndGet(current -> Math.max(current, uptoSequence));
    }

    /** The last sequence number {@code userId} has read in {@code channelId} (0 if never read). */
    public long lastRead(ChannelId channelId, UserId userId) {
        ConcurrentHashMap<UserId, AtomicLong> channelReads = lastReadSequence.get(channelId);
        if (channelReads == null) {
            return 0L;
        }
        AtomicLong position = channelReads.get(userId);
        return position == null ? 0L : position.get();
    }

    /**
     * Unread count for {@code userId} in {@code channelId}, given the channel's current
     * latest sequence number. O(1): unread count is pure arithmetic over two longs, regardless
     * of which fan-out strategy delivered the messages (see README section 14) -- there is no
     * scan of message history involved.
     */
    public long unreadCount(ChannelId channelId, UserId userId, long latestSequence) {
        long read = lastRead(channelId, userId);
        return Math.max(0L, latestSequence - read);
    }
}
