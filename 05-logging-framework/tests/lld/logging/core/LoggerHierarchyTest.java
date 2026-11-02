package lld.logging.core;

import lld.logging.appender.RecordingAppender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each test builds its own fresh {@link LoggerContext} -- deliberately never
 * touching {@link LoggerFactory}'s shared default context -- so tests cannot
 * leak configuration into one another (see {@link LoggerFactory}'s javadoc).
 */
class LoggerHierarchyTest {

    @Test
    void childInheritsParentLevelWhenNotExplicitlySet() {
        LoggerContext context = new LoggerContext();
        context.getRootLogger().setLevel(LogLevel.WARN);

        Logger child = context.getLogger("com.app.service.OrderService");

        assertEquals(LogLevel.WARN, child.getEffectiveLevel());
    }

    @Test
    void explicitChildLevelOverridesInheritedAncestorLevel() {
        LoggerContext context = new LoggerContext();
        context.getRootLogger().setLevel(LogLevel.WARN);

        Logger parent = context.getLogger("com.app.service");
        parent.setLevel(LogLevel.DEBUG);
        Logger child = context.getLogger("com.app.service.OrderService");

        assertEquals(LogLevel.DEBUG, child.getEffectiveLevel(),
                "child with no explicit level should inherit from its nearest ancestor, not skip to root");
    }

    @Test
    void sameNameAlwaysReturnsSameLoggerInstance() {
        LoggerContext context = new LoggerContext();
        Logger a = context.getLogger("com.app.Foo");
        Logger b = context.getLogger("com.app.Foo");
        assertTrue(a == b, "LoggerContext must return the same Logger instance for a repeated name");
    }

    @Test
    void additiveLoggerSendsEventToOwnAndAncestorAppenders() {
        LoggerContext context = new LoggerContext();
        RecordingAppender rootAppender = new RecordingAppender("root");
        RecordingAppender childAppender = new RecordingAppender("child");
        context.getRootLogger().setLevel(LogLevel.INFO);
        context.getRootLogger().addAppender(rootAppender);

        Logger child = context.getLogger("com.app.service");
        child.addAppender(childAppender);

        child.info("hello");

        assertEquals(1, rootAppender.size(), "additive logger must propagate events to ancestor appenders");
        assertEquals(1, childAppender.size());
        assertEquals("hello", rootAppender.getEvents().get(0).getMessage());
    }

    @Test
    void nonAdditiveLoggerDoesNotSendEventToAncestorAppenders() {
        LoggerContext context = new LoggerContext();
        RecordingAppender rootAppender = new RecordingAppender("root");
        RecordingAppender childAppender = new RecordingAppender("child");
        context.getRootLogger().setLevel(LogLevel.INFO);
        context.getRootLogger().addAppender(rootAppender);

        Logger child = context.getLogger("com.app.audit");
        child.addAppender(childAppender);
        child.setAdditive(false);

        child.info("audit entry");

        assertEquals(0, rootAppender.size(), "non-additive logger must not propagate to ancestor appenders");
        assertEquals(1, childAppender.size());
    }

    @Test
    void messageBelowEffectiveLevelIsNotDispatchedToAnyAppender() {
        LoggerContext context = new LoggerContext();
        RecordingAppender appender = new RecordingAppender();
        context.getRootLogger().setLevel(LogLevel.INFO);
        context.getRootLogger().addAppender(appender);

        Logger logger = context.getLogger("com.app.Noisy");
        logger.debug("should be filtered out");

        assertEquals(0, appender.size());
        assertFalse(logger.isEnabledFor(LogLevel.DEBUG));
    }

    @Test
    void messageAtOrAboveEffectiveLevelIsDispatched() {
        LoggerContext context = new LoggerContext();
        RecordingAppender appender = new RecordingAppender();
        context.getRootLogger().setLevel(LogLevel.INFO);
        context.getRootLogger().addAppender(appender);

        Logger logger = context.getLogger("com.app.Chatty");
        logger.warn("should pass through");

        assertEquals(1, appender.size());
        assertEquals(LogLevel.WARN, appender.getEvents().get(0).getLevel());
    }

    @Test
    void grandparentLevelIsInheritedAcrossMultipleHopsWhenInBetweenLoggersHaveNoExplicitLevel() {
        LoggerContext context = new LoggerContext();
        context.getRootLogger().setLevel(LogLevel.ERROR);

        // "com" and "com.app" are implicitly created with no explicit level.
        Logger deep = context.getLogger("com.app.service.billing.Invoice");

        assertEquals(LogLevel.ERROR, deep.getEffectiveLevel());
    }
}
