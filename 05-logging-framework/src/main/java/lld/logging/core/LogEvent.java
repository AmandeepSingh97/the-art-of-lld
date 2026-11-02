package lld.logging.core;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable value object representing a single logging event. This is the
 * unit of work passed from {@link Logger} through {@link lld.logging.filter.Filter}s,
 * into {@link lld.logging.appender.Appender}s, and -- in the async case --
 * across the producer/consumer queue boundary to the background writer
 * thread.
 *
 * <p><b>Why immutable:</b> a {@code LogEvent} is read by at least two threads
 * in the async pipeline: the calling (producer) thread that creates it, and
 * the background writer (consumer) thread that formats and writes it.
 * Immutability means no synchronization is required to safely publish/read
 * the event across that thread boundary; the only synchronization needed is
 * the one already provided by the {@link java.util.concurrent.BlockingQueue}
 * (its internal locks establish the happens-before edge between {@code put}
 * and {@code take}/{@code poll}).
 *
 * <p>The throwable reference is intentionally not deep-copied: exceptions are
 * effectively append-only objects once thrown, and we only ever read their
 * stack trace, so sharing the reference across threads is safe in practice.
 */
public final class LogEvent {

    private final String loggerName;
    private final LogLevel level;
    private final String message;
    private final Throwable throwable;
    private final long timestampMillis;
    private final String threadName;

    public LogEvent(String loggerName, LogLevel level, String message, Throwable throwable) {
        this.loggerName = Objects.requireNonNull(loggerName, "loggerName");
        this.level = Objects.requireNonNull(level, "level");
        this.message = Objects.requireNonNull(message, "message");
        this.throwable = throwable;
        this.timestampMillis = System.currentTimeMillis();
        // Captured eagerly at creation time (on the producer thread) because
        // the event may be formatted later, on the background writer thread,
        // where Thread.currentThread().getName() would report the wrong
        // thread for an async appender.
        this.threadName = Thread.currentThread().getName();
    }

    public String getLoggerName() {
        return loggerName;
    }

    public LogLevel getLevel() {
        return level;
    }

    public String getMessage() {
        return message;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public String getThreadName() {
        return threadName;
    }

    public Instant getTimestamp() {
        return Instant.ofEpochMilli(timestampMillis);
    }

    @Override
    public String toString() {
        return "LogEvent{" +
                "logger='" + loggerName + '\'' +
                ", level=" + level +
                ", message='" + message + '\'' +
                ", thread='" + threadName + '\'' +
                '}';
    }
}
