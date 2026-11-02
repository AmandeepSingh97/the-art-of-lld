package lld.slackchat.pagination;

/** Direction of traversal relative to the cursor in a {@link PageRequest}. */
public enum Direction {
    /** Fetch messages older than the cursor (scrolling up through history). */
    BACKWARD,
    /** Fetch messages newer than the cursor (catching up on new activity). */
    FORWARD
}
