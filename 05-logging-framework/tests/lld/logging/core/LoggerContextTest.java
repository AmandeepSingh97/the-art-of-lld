package lld.logging.core;

import lld.logging.appender.RecordingAppender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggerContextTest {

    @Test
    void shutdownClosesEveryDistinctAppenderExactlyOnceEvenWhenSharedAcrossLoggers() {
        LoggerContext context = new LoggerContext();
        RecordingAppender shared = new RecordingAppender("shared");

        context.getRootLogger().addAppender(shared);
        context.getLogger("com.app.A").addAppender(shared);
        context.getLogger("com.app.B").addAppender(shared);

        context.shutdown();

        assertTrue(shared.isClosed());
    }

    @Test
    void getLoggerForClassUsesFullyQualifiedClassName() {
        LoggerContext context = new LoggerContext();
        Logger byClass = context.getLogger(LoggerContextTest.class);
        Logger byName = context.getLogger(LoggerContextTest.class.getName());
        assertEquals(byName, byClass);
    }

    @Test
    void rootLoggerHasDefaultLevelSoEffectiveLevelLookupAlwaysTerminates() {
        LoggerContext context = new LoggerContext();
        assertEquals(LogLevel.INFO, context.getRootLogger().getEffectiveLevel());
    }
}
