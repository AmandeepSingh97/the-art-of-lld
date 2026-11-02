package lld.logging.core;

/**
 * Ordinal severity levels for the logging framework, modelled after log4j /
 * java.util.logging.
 *
 * <p><b>Design note (discussed in README "Data Structures and Algorithms"):</b>
 * two designs were considered for level filtering:
 * <ol>
 *   <li><b>Chain of Responsibility</b> — each level is a handler object that
 *       either processes the event or forwards it to the next (more severe)
 *       handler. This is how some textbook logging examples model levels,
 *       and how java.util.logging.Filter chains work.</li>
 *   <li><b>Simple ordinal comparison</b> — each level carries an integer
 *       severity; a logger is "enabled" for a message if
 *       {@code messageLevel.severity() >= configuredThreshold.severity()}.</li>
 * </ol>
 * We chose ordinal comparison. Chain of Responsibility adds object allocation
 * and indirection for a decision that is a single integer comparison made on
 * every single log call (a hot path, potentially millions of times/sec).
 * CoR earns its keep when the set of handlers is dynamic/open-ended and each
 * handler does non-trivial work (e.g. validation pipelines); log levels are a
 * small, closed, totally-ordered set, which is precisely what enums + ordinal
 * comparison are good at. We keep {@link lld.logging.filter.Filter} as a
 * *separate*, genuinely open-ended extension point for CoR-style predicate
 * chains (filter on MDC fields, marker, regex, etc.) layered on top of the
 * cheap level check, rather than conflating the two concerns.
 */
public enum LogLevel {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    FATAL(5),
    /** Sentinel used to disable a logger entirely ("OFF" in log4j). */
    OFF(Integer.MAX_VALUE);

    private final int severity;

    LogLevel(int severity) {
        this.severity = severity;
    }

    public int severity() {
        return severity;
    }

    /**
     * Returns true if a message logged at {@code this} level should be
     * emitted given a configured {@code threshold}. A message is loggable
     * when its own severity is at least as high as the threshold's.
     *
     * <p>Example: threshold = INFO, this = DEBUG -&gt; not loggable.
     * threshold = INFO, this = WARN -&gt; loggable.
     */
    public boolean isAsSevereAs(LogLevel threshold) {
        return this.severity >= threshold.severity;
    }
}
