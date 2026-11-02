package lld.lfucache;

/**
 * Thrown when a cache is constructed with a structurally invalid capacity
 * (negative). Capacity {@code 0} is accepted as a legal, if degenerate,
 * "cache that stores nothing" and therefore does not trigger this exception.
 *
 * <p>This is an unchecked exception: an invalid capacity is a programming
 * error (a precondition violation), not a recoverable runtime condition, so
 * forcing every call site to catch it would add noise without value — the
 * same reasoning the JDK applies to {@link IllegalArgumentException}, which
 * this type extends so existing {@code catch (IllegalArgumentException)}
 * handlers keep working.
 */
public class InvalidCacheCapacityException extends IllegalArgumentException {

    public InvalidCacheCapacityException(int capacity) {
        super("Cache capacity must be >= 0, got: " + capacity);
    }
}
