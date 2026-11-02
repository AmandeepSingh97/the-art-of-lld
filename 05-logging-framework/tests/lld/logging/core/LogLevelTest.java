package lld.logging.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogLevelTest {

    @Test
    void severityIsStrictlyIncreasingAcrossLevels() {
        LogLevel[] ordered = {
                LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL
        };
        for (int i = 1; i < ordered.length; i++) {
            assertTrue(ordered[i].severity() > ordered[i - 1].severity(),
                    ordered[i] + " should be more severe than " + ordered[i - 1]);
        }
    }

    @Test
    void isAsSevereAsIsTrueWhenMessageLevelMeetsOrExceedsThreshold() {
        assertTrue(LogLevel.WARN.isAsSevereAs(LogLevel.INFO));
        assertTrue(LogLevel.INFO.isAsSevereAs(LogLevel.INFO));
    }

    @Test
    void isAsSevereAsIsFalseWhenMessageLevelIsBelowThreshold() {
        assertFalse(LogLevel.DEBUG.isAsSevereAs(LogLevel.INFO));
        assertFalse(LogLevel.TRACE.isAsSevereAs(LogLevel.WARN));
    }

    @Test
    void offThresholdDisablesEveryRealLevel() {
        for (LogLevel level : new LogLevel[]{LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO,
                LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL}) {
            assertFalse(level.isAsSevereAs(LogLevel.OFF), level + " must never be loggable when threshold is OFF");
        }
    }
}
