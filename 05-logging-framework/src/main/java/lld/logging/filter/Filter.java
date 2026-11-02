package lld.logging.filter;

import lld.logging.core.LogEvent;

/**
 * Open-ended predicate hook evaluated after the cheap ordinal level check
 * has already passed. This is the genuinely extensible, Chain-of-Responsibility
 * style extension point referenced in {@link lld.logging.core.LogLevel}'s
 * javadoc: filters can be composed/chained, added/removed at runtime, and
 * each one decides independently whether to accept an event (e.g. filter by
 * MDC/correlation id, message content regex, marker, rate limiting).
 *
 * <p>Filters must be side-effect-free and fast: they run on the calling
 * thread (before the event ever reaches a queue or an appender), so a slow
 * filter blocks the application thread, not a background worker.
 */
@FunctionalInterface
public interface Filter {
    boolean isLoggable(LogEvent event);
}
