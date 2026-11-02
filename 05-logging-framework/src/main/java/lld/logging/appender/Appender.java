package lld.logging.appender;

import lld.logging.core.LogEvent;

/**
 * Strategy interface for "where does a log event go": console, a file, a
 * rolling file, a network socket, an async decorator around another
 * appender, etc. This is the classic Strategy pattern -- {@link
 * lld.logging.core.Logger} is configured with one or more {@code Appender}
 * instances and is completely decoupled from how/where they persist events.
 *
 * <p><b>Thread-safety contract:</b> {@link #append(LogEvent)} MUST be safe
 * to call concurrently from multiple application threads, because multiple
 * threads may log through the same {@link lld.logging.core.Logger} (or
 * through different loggers that share the same appender instance, which is
 * normal -- e.g. one shared file appender attached to many loggers).
 * Implementations are responsible for their own internal synchronization;
 * see {@link AbstractAppender} and {@link FileAppender} for the two
 * strategies used in this codebase (immutable/stateless vs. explicit lock).
 */
public interface Appender extends AutoCloseable {

    String getName();

    /**
     * Writes (or enqueues, for async appenders) the given event. Must not
     * throw for ordinary I/O hiccups that the appender itself defines a
     * recovery/drop policy for; genuinely unrecoverable conditions may throw
     * {@link lld.logging.exception.AppenderException}.
     */
    void append(LogEvent event);

    /** Forces any buffered data to be written to the underlying destination. */
    void flush();

    /**
     * Releases resources (file handles, background threads). Must flush
     * first. Idempotent: calling close() more than once is safe.
     */
    @Override
    void close();
}
