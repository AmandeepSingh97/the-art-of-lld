package lld.logging.filter;

import lld.logging.core.LogEvent;
import lld.logging.core.LogLevel;

import java.util.Objects;

/**
 * A simple {@link Filter} that accepts events within an inclusive
 * [minLevel, maxLevel] severity band. Useful for routing, e.g., only
 * WARN..ERROR to a pager-integration appender while everything still goes to
 * the general file appender.
 */
public final class LevelRangeFilter implements Filter {

    private final LogLevel minLevel;
    private final LogLevel maxLevel;

    public LevelRangeFilter(LogLevel minLevel, LogLevel maxLevel) {
        this.minLevel = Objects.requireNonNull(minLevel, "minLevel");
        this.maxLevel = Objects.requireNonNull(maxLevel, "maxLevel");
        if (minLevel.severity() > maxLevel.severity()) {
            throw new IllegalArgumentException("minLevel must not be more severe than maxLevel");
        }
    }

    @Override
    public boolean isLoggable(LogEvent event) {
        int s = event.getLevel().severity();
        return s >= minLevel.severity() && s <= maxLevel.severity();
    }
}
