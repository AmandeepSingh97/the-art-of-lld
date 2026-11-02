package lld.logging.layout;

import lld.logging.core.LogEvent;

/**
 * Strategy interface for turning a {@link LogEvent} into a textual
 * representation. Decouples "what is in the event" (domain) from
 * "how it should look on the wire / on disk / on screen" (presentation),
 * mirroring log4j's {@code Layout}/{@code PatternLayout} split.
 *
 * <p>Implementations must be stateless or internally thread-safe: the same
 * {@code Layout} instance is shared by all callers of an {@link
 * lld.logging.appender.Appender} and, for an {@link
 * lld.logging.appender.AsyncAppender}, is invoked from the single background
 * writer thread only (never concurrently), so a stateless implementation is
 * sufficient and simplest.
 */
@FunctionalInterface
public interface Layout {
    String format(LogEvent event);
}
