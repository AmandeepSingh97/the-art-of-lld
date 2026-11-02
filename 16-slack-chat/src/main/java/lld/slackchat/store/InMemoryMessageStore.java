package lld.slackchat.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.Message;
import lld.slackchat.model.MessageId;
import lld.slackchat.model.UserId;
import lld.slackchat.pagination.Direction;
import lld.slackchat.pagination.MessagePage;
import lld.slackchat.pagination.PageRequest;

/**
 * In-memory {@link MessageStore} backed by one {@link ConcurrentSkipListMap} per channel.
 *
 * <p><b>Why {@code ConcurrentSkipListMap}:</b> it is a concurrent, sorted map with O(log n)
 * get/put/range-view operations and lock-free reads, which is exactly what keyset pagination
 * needs (headMap/tailMap views are O(log n) to obtain and then streamed lazily). A plain
 * {@code ArrayList} would need external synchronization for concurrent appends and O(n)
 * insert-in-the-middle semantics do not even apply here (appends are always at the tail by
 * sequence number) -- but a {@code synchronized ArrayList} would still serialize all readers
 * and writers on one lock, including concurrent pagination reads from different users. A
 * {@code TreeMap} is sorted but not thread-safe without an external lock. {@code
 * ConcurrentSkipListMap} is the structure purpose-built for "sorted + concurrent".
 *
 * <p><b>Why no single global lock is required for appends:</b> the per-channel
 * {@link AtomicLong} sequence generator is the <i>only</i> coordination point. Each thread
 * calls {@code incrementAndGet()} to claim a unique sequence number and then performs an
 * independent {@code put(seq, message)} with that key -- two threads can never claim the same
 * key, so their puts never conflict, and no additional lock is needed around the pair of
 * operations. The small window between "sequence claimed" and "message visible in the map" is
 * also harmless for this domain: a concurrent reader simply does not see that message yet,
 * which is indistinguishable from it not having been sent a few microseconds earlier.
 */
public final class InMemoryMessageStore implements MessageStore {

    private final Map<ChannelId, ConcurrentSkipListMap<Long, Message>> channelLogs = new ConcurrentHashMap<>();
    private final Map<ChannelId, AtomicLong> sequenceGenerators = new ConcurrentHashMap<>();
    private final Map<MessageId, Message> globalIndex = new ConcurrentHashMap<>();

    @Override
    public Message append(ChannelId channelId, UserId senderId, String content, Set<UserId> mentionedUserIds) {
        ConcurrentSkipListMap<Long, Message> log =
                channelLogs.computeIfAbsent(channelId, c -> new ConcurrentSkipListMap<>());
        AtomicLong seqGen = sequenceGenerators.computeIfAbsent(channelId, c -> new AtomicLong(0L));
        long sequence = seqGen.incrementAndGet();

        Message message = new Message(
                MessageId.generate(), channelId, senderId, sequence, content, mentionedUserIds, Instant.now());
        log.put(sequence, message);
        globalIndex.put(message.id(), message);
        return message;
    }

    @Override
    public Optional<Message> findById(MessageId messageId) {
        return Optional.ofNullable(globalIndex.get(messageId));
    }

    @Override
    public MessagePage getPage(ChannelId channelId, PageRequest request) {
        ConcurrentSkipListMap<Long, Message> log = channelLogs.get(channelId);
        if (log == null || log.isEmpty()) {
            return MessagePage.empty();
        }

        int fetchLimit = request.limit() + 1; // fetch one extra to detect "hasMore" cheaply
        List<Message> collected = new ArrayList<>(request.limit());

        if (request.direction() == Direction.BACKWARD) {
            NavigableMap<Long, Message> view = request.cursorSequence().isPresent()
                    ? log.headMap(request.cursorSequence().getAsLong(), false)
                    : log;
            for (Map.Entry<Long, Message> entry : view.descendingMap().entrySet()) {
                if (collected.size() == fetchLimit) {
                    break;
                }
                collected.add(entry.getValue());
            }
        } else {
            NavigableMap<Long, Message> view = request.cursorSequence().isPresent()
                    ? log.tailMap(request.cursorSequence().getAsLong(), false)
                    : log;
            for (Map.Entry<Long, Message> entry : view.entrySet()) {
                if (collected.size() == fetchLimit) {
                    break;
                }
                collected.add(entry.getValue());
            }
        }

        boolean hasMore = collected.size() > request.limit();
        if (hasMore) {
            collected.remove(collected.size() - 1);
        }
        if (collected.isEmpty()) {
            return MessagePage.empty();
        }
        long nextCursor = collected.get(collected.size() - 1).sequence();
        return new MessagePage(collected, java.util.OptionalLong.of(nextCursor), hasMore);
    }

    @Override
    public long latestSequence(ChannelId channelId) {
        AtomicLong seqGen = sequenceGenerators.get(channelId);
        return seqGen == null ? 0L : seqGen.get();
    }
}
