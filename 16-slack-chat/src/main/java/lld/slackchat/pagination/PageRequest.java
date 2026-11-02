package lld.slackchat.pagination;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * A keyset ("seek method") pagination request over a channel's message history, as opposed to
 * offset/limit pagination.
 *
 * <p>We use the message {@code sequence} (a dense, monotonically increasing per-channel long)
 * as the cursor. This is deliberately chosen over offset-based pagination
 * ({@code OFFSET n LIMIT m}): offset pagination degrades to O(n) per page as a channel grows
 * and produces duplicate/missing rows when new messages are inserted while a user is
 * scrolling. Keyset pagination is O(log n + limit) per page (backed by
 * {@link java.util.concurrent.ConcurrentSkipListMap}) and is stable under concurrent writes.
 * See README section 10 for the full complexity comparison.
 */
public record PageRequest(OptionalLong cursorSequence, int limit, Direction direction) {

    public PageRequest {
        Objects.requireNonNull(cursorSequence, "cursorSequence");
        Objects.requireNonNull(direction, "direction");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        if (limit > 500) {
            throw new IllegalArgumentException("limit must not exceed 500 per page, got " + limit);
        }
    }

    /** First page, most recent messages first (typical "open a channel" view). */
    public static PageRequest latest(int limit) {
        return new PageRequest(OptionalLong.empty(), limit, Direction.BACKWARD);
    }

    /** Page of messages strictly older than {@code cursorSequence} (scroll up). */
    public static PageRequest before(long cursorSequence, int limit) {
        return new PageRequest(OptionalLong.of(cursorSequence), limit, Direction.BACKWARD);
    }

    /** Page of messages strictly newer than {@code cursorSequence} (catch up / sync). */
    public static PageRequest after(long cursorSequence, int limit) {
        return new PageRequest(OptionalLong.of(cursorSequence), limit, Direction.FORWARD);
    }
}
