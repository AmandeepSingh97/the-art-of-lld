package lld.logging.filter;

import lld.logging.core.LogEvent;
import lld.logging.core.LogLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelRangeFilterTest {

    @Test
    void acceptsEventsWithinInclusiveRange() {
        LevelRangeFilter filter = new LevelRangeFilter(LogLevel.WARN, LogLevel.ERROR);

        assertTrue(filter.isLoggable(new LogEvent("x", LogLevel.WARN, "m", null)));
        assertTrue(filter.isLoggable(new LogEvent("x", LogLevel.ERROR, "m", null)));
    }

    @Test
    void rejectsEventsOutsideRange() {
        LevelRangeFilter filter = new LevelRangeFilter(LogLevel.WARN, LogLevel.ERROR);

        assertFalse(filter.isLoggable(new LogEvent("x", LogLevel.INFO, "m", null)));
        assertFalse(filter.isLoggable(new LogEvent("x", LogLevel.FATAL, "m", null)));
    }

    @Test
    void rejectsInvertedRangeAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new LevelRangeFilter(LogLevel.ERROR, LogLevel.WARN));
    }
}
