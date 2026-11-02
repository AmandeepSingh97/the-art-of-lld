package lld.logging.core;

import lld.logging.appender.Appender;
import lld.logging.config.LoggerConfig;
import lld.logging.config.LoggingConfiguration;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the logger hierarchy (the "repository" in log4j terminology): creates
 * loggers on demand, wires each new logger to its parent by splitting the
 * dot-separated name, and applies a {@link LoggingConfiguration}.
 *
 * <p>This is deliberately an instantiable class, not a forced static
 * singleton: tests create their own private {@code LoggerContext} so
 * configuration in one test can never leak into another (a real bug class
 * with static/global logging registries). {@link LoggerFactory} is a thin
 * convenience facade that holds one well-known default instance for
 * application code that just wants {@code LoggerFactory.getLogger(...)}
 * without plumbing a context through every constructor.
 *
 * <h2>Thread-safety</h2>
 * {@link #getLogger(String)} is the hot path, callable concurrently from any
 * thread (e.g. two threads both touching a brand-new class's logger for the
 * first time). It uses {@link ConcurrentHashMap#computeIfAbsent} keyed by
 * name, which guarantees at most one {@link Logger} instance is ever
 * published per name even under concurrent first-access (the compute
 * function for a given key runs atomically with respect to other callers
 * racing on that same key). Ancestor loggers are created via recursive calls
 * to the same {@code computeIfAbsent}-backed method, so the whole chain is
 * built safely regardless of what order concurrent callers ask for
 * different depths of the same hierarchy.
 */
public final class LoggerContext {

    private final Map<String, Logger> loggers = new ConcurrentHashMap<>();
    private final Logger root;

    public LoggerContext() {
        this.root = new Logger(Logger.ROOT_LOGGER_NAME, null);
        this.root.setLevel(LogLevel.INFO);
        loggers.put(Logger.ROOT_LOGGER_NAME, root);
    }

    public Logger getRootLogger() {
        return root;
    }

    public Logger getLogger(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            return root;
        }
        Logger existing = loggers.get(name);
        if (existing != null) {
            return existing;
        }
        Logger parent = getLogger(parentNameOf(name));
        return loggers.computeIfAbsent(name, n -> new Logger(n, parent));
    }

    public Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName());
    }

    private static String parentNameOf(String name) {
        int lastDot = name.lastIndexOf('.');
        return lastDot < 0 ? Logger.ROOT_LOGGER_NAME : name.substring(0, lastDot);
    }

    /**
     * Applies a {@link LoggingConfiguration}: sets the root's level/appenders,
     * then for each named {@link LoggerConfig} fetches/creates that logger
     * (materializing the hierarchy) and applies its level, additivity, and
     * appenders. Re-applying a configuration (e.g. for hot-reload) simply
     * overwrites prior settings; it does not attempt to diff/remove
     * appenders that a previous configuration added but the new one omits --
     * callers that need that should close() old appenders themselves before
     * reconfiguring, since an Appender may own live resources (files,
     * threads).
     */
    public void configure(LoggingConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        root.setLevel(configuration.getRootLevel());
        for (Appender appender : configuration.getRootAppenders()) {
            root.addAppender(appender);
        }
        for (LoggerConfig loggerConfig : configuration.getLoggerConfigs()) {
            Logger logger = getLogger(loggerConfig.name());
            if (loggerConfig.level() != null) {
                logger.setLevel(loggerConfig.level());
            }
            logger.setAdditive(loggerConfig.additive());
            for (Appender appender : loggerConfig.appenders()) {
                logger.addAppender(appender);
            }
        }
    }

    /**
     * Closes every distinct {@link Appender} reachable from every logger in
     * the hierarchy (de-duplicated, since the same shared appender instance
     * is commonly attached to several loggers). Call this during application
     * shutdown so {@link lld.logging.appender.AsyncAppender}s get a chance to
     * drain their queues and {@link lld.logging.appender.FileAppender}s flush
     * and release file handles -- see README "Concurrency and Thread Safety"
     * for why this matters for last-events-before-crash durability.
     */
    public void shutdown() {
        java.util.Set<Appender> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Logger logger : loggers.values()) {
            for (Appender appender : appendersOf(logger)) {
                if (seen.add(appender)) {
                    appender.close();
                }
            }
        }
    }

    private static Iterable<Appender> appendersOf(Logger logger) {
        // Package-visible helper kept local: Logger does not expose a public
        // "list my appenders" getter (callers should not mutate it), but
        // LoggerContext is allowed to reach in for shutdown purposes.
        return logger.snapshotAppenders();
    }
}
