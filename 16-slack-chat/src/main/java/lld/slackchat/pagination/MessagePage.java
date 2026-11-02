package lld.slackchat.pagination;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import lld.slackchat.model.Message;

/**
 * A single page of channel history, returned in the same order the caller asked for
 * ({@code BACKWARD} pages are newest-first, {@code FORWARD} pages are oldest-first), plus the
 * cursor to request the next page.
 */
public record MessagePage(List<Message> messages, OptionalLong nextCursor, boolean hasMore) {

    public MessagePage {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(nextCursor, "nextCursor");
    }

    public static MessagePage empty() {
        return new MessagePage(List.of(), OptionalLong.empty(), false);
    }
}
