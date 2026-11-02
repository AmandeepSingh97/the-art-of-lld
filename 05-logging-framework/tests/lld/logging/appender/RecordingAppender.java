package lld.logging.appender;

import lld.logging.core.LogEvent;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only {@link Appender} that records every event it receives, in order,
 * so tests can assert on exact content/ordering without touching the
 * filesystem or console. Thread-safe (CopyOnWriteArrayList) so it can be
 * shared across producer threads in concurrency tests.
 */
public final class RecordingAppender implements Appender {

    private final String name;
    private final List<LogEvent> events = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    public RecordingAppender(String name) {
        this.name = name;
    }

    public RecordingAppender() {
        this("recording");
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void append(LogEvent event) {
        events.add(event);
    }

    @Override
    public void flush() {
        // nothing buffered
    }

    @Override
    public void close() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    public List<LogEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public int size() {
        return events.size();
    }
}
