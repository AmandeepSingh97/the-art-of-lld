package lld.logging.core;

/**
 * Static convenience facade (Factory pattern) over a single default {@link
 * LoggerContext}, mirroring the ergonomics application code expects from
 * log4j's {@code LogManager.getLogger(...)}. Kept intentionally thin: all
 * real behavior lives in {@link LoggerContext} / {@link Logger}, so this
 * class is not a "god class" or a hidden-state singleton that resists
 * testing -- tests are expected to construct their own {@link LoggerContext}
 * directly rather than go through this facade, precisely to avoid shared
 * static state between test cases (see {@code LoggerHierarchyTest}).
 */
public final class LoggerFactory {

    private static final LoggerContext DEFAULT_CONTEXT = new LoggerContext();

    private LoggerFactory() {
    }

    public static LoggerContext getDefaultContext() {
        return DEFAULT_CONTEXT;
    }

    public static Logger getLogger(String name) {
        return DEFAULT_CONTEXT.getLogger(name);
    }

    public static Logger getLogger(Class<?> clazz) {
        return DEFAULT_CONTEXT.getLogger(clazz);
    }
}
