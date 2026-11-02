package lld.logging.appender;

/**
 * Policy applied by {@link AsyncAppender} when its bounded internal queue is
 * full and the producer (application thread) calls {@link
 * AsyncAppender#append}.
 */
public enum BackpressurePolicy {
    /**
     * Block the calling (application) thread until space is available.
     * Strongest durability guarantee (no event is ever silently lost while
     * the appender is running) at the cost of coupling application
     * throughput to logging throughput under sustained overload -- exactly
     * the trade-off async logging was meant to avoid, so this is usually
     * only appropriate for audit-grade logging that must never drop events.
     */
    BLOCK,

    /**
     * Drop the new (incoming) event and return immediately. Preserves
     * application responsiveness; favors recent application throughput over
     * completeness of the log stream. A dropped-event counter should be
     * incremented so operators can see that loss occurred (see README
     * "Failure Scenarios").
     */
    DROP_NEW,

    /**
     * Evict the oldest queued event to make room for the new one. Useful
     * when the newest events are the most actionable (e.g. "what is
     * happening right now" dashboards) and stale queued events are less
     * valuable than fresh ones.
     */
    DISCARD_OLDEST
}
