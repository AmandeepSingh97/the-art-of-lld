package lld.logging.config;

import lld.logging.appender.RecordingAppender;
import lld.logging.core.LogLevel;
import lld.logging.core.Logger;
import lld.logging.core.LoggerContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LoggingConfigurationTest {

    @Test
    void appliesRootLevelAndRootAppenderToContext() {
        RecordingAppender rootAppender = new RecordingAppender("root");
        LoggingConfiguration configuration = LoggingConfiguration.builder()
                .rootLevel(LogLevel.WARN)
                .addRootAppender(rootAppender)
                .build();

        LoggerContext context = new LoggerContext();
        context.configure(configuration);

        assertEquals(LogLevel.WARN, context.getRootLogger().getEffectiveLevel());

        context.getLogger("com.app.Anything").warn("x");
        assertEquals(1, rootAppender.size());
    }

    @Test
    void perLoggerConfigurationSetsLevelAdditivityAndAppenders() {
        RecordingAppender auditAppender = new RecordingAppender("audit");
        RecordingAppender rootAppender = new RecordingAppender("root");

        LoggingConfiguration configuration = LoggingConfiguration.builder()
                .rootLevel(LogLevel.INFO)
                .addRootAppender(rootAppender)
                .forLogger("com.app.audit")
                    .level(LogLevel.DEBUG)
                    .additive(false)
                    .addAppender(auditAppender)
                    .and()
                .build();

        LoggerContext context = new LoggerContext();
        context.configure(configuration);

        Logger auditLogger = context.getLogger("com.app.audit");
        assertEquals(LogLevel.DEBUG, auditLogger.getEffectiveLevel());
        assertFalse(auditLogger.isAdditive());

        auditLogger.debug("sensitive action");

        assertEquals(1, auditAppender.size());
        assertEquals(0, rootAppender.size(), "non-additive logger config must not leak to root appender");
    }
}
