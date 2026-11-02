package lld.logging.layout;

import lld.logging.core.LogEvent;
import lld.logging.core.LogLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternLayoutTest {

    @Test
    void defaultPatternContainsLevelLoggerThreadAndMessage() {
        PatternLayout layout = new PatternLayout();
        LogEvent event = new LogEvent("com.app.Foo", LogLevel.INFO, "hello world", null);

        String formatted = layout.format(event);

        assertTrue(formatted.contains("INFO"));
        assertTrue(formatted.contains("com.app.Foo"));
        assertTrue(formatted.contains("hello world"));
        assertTrue(formatted.endsWith(System.lineSeparator()));
    }

    @Test
    void customPatternExpandsOnlyRequestedTokensInOrder() {
        PatternLayout layout = new PatternLayout("[%p] %m");
        LogEvent event = new LogEvent("ignored.logger", LogLevel.ERROR, "boom", null);

        assertEquals("[ERROR] boom", layout.format(event));
    }

    @Test
    void literalPercentIsEscapedAndNotTreatedAsConversion() {
        PatternLayout layout = new PatternLayout("100%% done: %m");
        LogEvent event = new LogEvent("x", LogLevel.INFO, "ok", null);

        assertEquals("100% done: ok", layout.format(event));
    }

    @Test
    void messageTokenAppendsStackTraceWhenThrowablePresent() {
        PatternLayout layout = new PatternLayout("%m");
        RuntimeException ex = new RuntimeException("kaboom");
        LogEvent event = new LogEvent("x", LogLevel.ERROR, "failed", ex);

        String formatted = layout.format(event);

        assertTrue(formatted.startsWith("failed"));
        assertTrue(formatted.contains("RuntimeException: kaboom"));
    }

    @Test
    void unknownConversionCharacterIsEchoedVerbatim() {
        PatternLayout layout = new PatternLayout("%q");
        LogEvent event = new LogEvent("x", LogLevel.INFO, "msg", null);

        assertEquals("%q", layout.format(event));
    }
}
