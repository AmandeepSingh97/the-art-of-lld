package lld.logging.appender;

import lld.logging.core.LogEvent;
import lld.logging.core.LogLevel;
import lld.logging.filter.Filter;
import lld.logging.layout.Layout;
import lld.logging.layout.SimpleLayout;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Common skeleton for appenders: name, layout, an appender-local level
 * threshold (independent of any logger's level -- e.g. "log everything to
 * the console at DEBUG+, but only WARN+ to the alerting appender"), and a
 * chain of {@link Filter}s.
 *
 * <p>Template Method: {@link #append(LogEvent)} does the common
 * threshold/filter gating and delegates the actual write to {@link
 * #doAppend(String, LogEvent)}, which subclasses implement.
 */
public abstract class AbstractAppender implements Appender {

    private final String name;
    private volatile Layout layout;
    private volatile LogLevel threshold;
    // CopyOnWriteArrayList: filter lists are mutated rarely (at configuration
    // time) and read on every single log call from potentially many threads;
    // this optimizes for the read-heavy, write-rare access pattern without
    // needing external synchronization for iteration.
    private final CopyOnWriteArrayList<Filter> filters = new CopyOnWriteArrayList<>();

    protected AbstractAppender(String name, Layout layout, LogLevel threshold) {
        this.name = Objects.requireNonNull(name, "name");
        this.layout = layout != null ? layout : new SimpleLayout();
        this.threshold = threshold != null ? threshold : LogLevel.TRACE;
    }

    @Override
    public final String getName() {
        return name;
    }

    public void setLayout(Layout layout) {
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    public void setThreshold(LogLevel threshold) {
        this.threshold = Objects.requireNonNull(threshold, "threshold");
    }

    public void addFilter(Filter filter) {
        filters.add(Objects.requireNonNull(filter, "filter"));
    }

    @Override
    public final void append(LogEvent event) {
        if (!event.getLevel().isAsSevereAs(threshold)) {
            return;
        }
        for (Filter filter : filters) {
            if (!filter.isLoggable(event)) {
                return;
            }
        }
        String formatted = layout.format(event);
        doAppend(formatted, event);
    }

    /** Subclasses perform the actual write of the already-formatted line. */
    protected abstract void doAppend(String formattedMessage, LogEvent event);
}
