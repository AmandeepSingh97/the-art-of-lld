package lld.logging.core;

import lld.logging.appender.Appender;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A named logger in a dot-separated hierarchy, e.g. {@code "com.app.service.OrderService"}
 * has ancestors {@code "com.app.service"}, {@code "com.app"}, {@code "com"}, and the
 * ROOT logger -- mirroring log4j/java.util.logging's package-hierarchy model.
 *
 * <h2>Level inheritance</h2>
 * A logger's own {@link #level} may be {@code null}, meaning "inherit from
 * parent". {@link #getEffectiveLevel()} walks up the parent chain until it
 * finds a logger with an explicit level; the ROOT logger always has one
 * (defaulting to {@link LogLevel#INFO}), guaranteeing the walk terminates.
 * This lets operators set a broad default on ROOT and override just the
 * noisy/interesting subtree (e.g. set {@code com.app.payment} to DEBUG while
 * everything else stays at INFO) without touching every logger.
 *
 * <h2>Additivity</h2>
 * By default a logger is "additive": a log event is sent to its own
 * appenders AND to every ancestor's appenders (so attaching one appender to
 * ROOT gives every logger in the tree output "for free"). Setting {@code
 * additive = false} stops the walk at this logger -- useful when a subtree
 * should be routed to a completely different destination (e.g. an audit
 * logger that must NOT also go to the general application log).
 *
 * <h2>Thread-safety</h2>
 * <ul>
 *   <li>{@code level} is an {@link AtomicReference} so a reconfiguration
 *       thread can change it without any reader observing a torn/partial
 *       value, and readers (every log call) don't need a lock.</li>
 *   <li>{@code appenders} is a {@link CopyOnWriteArrayList}: appenders are
 *       added/removed rarely (at configuration time) and iterated on every
 *       single log call from potentially many application threads --
 *       exactly the read-heavy/write-rare profile CopyOnWriteArrayList is
 *       designed for, and iteration never throws
 *       {@code ConcurrentModificationException} even if reconfigured
 *       concurrently.</li>
 *   <li>{@code additive} is a plain {@code volatile boolean} -- single word,
 *       read on every call, written rarely; volatile is sufficient (no
 *       compound action needed).</li>
 * </ul>
 * The {@code parent} reference is set once at construction (by {@link
 * LoggerContext}) and never mutated afterwards, so no synchronization is
 * needed to read it.
 */
public final class Logger {

    /** Name used for the root of the hierarchy. */
    public static final String ROOT_LOGGER_NAME = "";

    private final String name;
    private final Logger parent;
    private final AtomicReference<LogLevel> level = new AtomicReference<>();
    private final CopyOnWriteArrayList<Appender> appenders = new CopyOnWriteArrayList<>();
    private volatile boolean additive = true;

    Logger(String name, Logger parent) {
        this.name = Objects.requireNonNull(name, "name");
        this.parent = parent;
    }

    public String getName() {
        return name;
    }

    public Logger getParent() {
        return parent;
    }

    public void setLevel(LogLevel level) {
        this.level.set(level);
    }

    /** The level explicitly set on this logger, or {@code null} if it inherits. */
    public LogLevel getLevel() {
        return level.get();
    }

    public LogLevel getEffectiveLevel() {
        for (Logger l = this; l != null; l = l.parent) {
            LogLevel lvl = l.level.get();
            if (lvl != null) {
                return lvl;
            }
        }
        // Unreachable in practice: LoggerContext always assigns ROOT a
        // level, but fail safe rather than NPE if misused directly.
        return LogLevel.INFO;
    }

    public void addAppender(Appender appender) {
        appenders.add(Objects.requireNonNull(appender, "appender"));
    }

    public void removeAppender(Appender appender) {
        appenders.remove(appender);
    }

    /**
     * Package-visible snapshot of this logger's own (non-inherited)
     * appenders, used by {@link LoggerContext#shutdown()}. Not exposed
     * publicly: external callers should go through {@link #addAppender}/
     * {@link #removeAppender} rather than mutate the list directly.
     */
    java.util.List<Appender> snapshotAppenders() {
        return java.util.List.copyOf(appenders);
    }

    public void setAdditive(boolean additive) {
        this.additive = additive;
    }

    public boolean isAdditive() {
        return additive;
    }

    public boolean isEnabledFor(LogLevel candidate) {
        return candidate.isAsSevereAs(getEffectiveLevel());
    }

    public void trace(String message) { log(LogLevel.TRACE, message, null); }
    public void debug(String message) { log(LogLevel.DEBUG, message, null); }
    public void info(String message) { log(LogLevel.INFO, message, null); }
    public void warn(String message) { log(LogLevel.WARN, message, null); }
    public void error(String message) { log(LogLevel.ERROR, message, null); }
    public void error(String message, Throwable t) { log(LogLevel.ERROR, message, t); }
    public void fatal(String message, Throwable t) { log(LogLevel.FATAL, message, t); }

    public void log(LogLevel level, String message, Throwable throwable) {
        // Cheap ordinal check BEFORE allocating a LogEvent or touching any
        // appender -- the whole point of level filtering is to make the
        // disabled case (e.g. TRACE calls in a production INFO-level
        // service) as close to free as possible.
        if (!isEnabledFor(level)) {
            return;
        }
        LogEvent event = new LogEvent(name, level, message, throwable);
        callAppenders(event);
    }

    private void callAppenders(LogEvent event) {
        for (Logger l = this; l != null; l = l.parent) {
            for (Appender appender : l.appenders) {
                appender.append(event);
            }
            if (!l.additive) {
                break;
            }
        }
    }
}
