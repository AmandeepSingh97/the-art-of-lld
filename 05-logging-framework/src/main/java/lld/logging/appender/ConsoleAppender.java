package lld.logging.appender;

import lld.logging.core.LogEvent;
import lld.logging.core.LogLevel;
import lld.logging.layout.Layout;

import java.io.PrintStream;

/**
 * Writes formatted log lines to {@link System#out} (or any {@link
 * PrintStream} supplied for testing, e.g. a {@code ByteArrayOutputStream}
 * wrapper). {@link PrintStream} is internally synchronized per-write, so no
 * additional locking is required here for correctness; concurrent writers
 * will not interleave partial lines, though relative ordering between
 * threads is whatever order they happen to acquire the stream's internal
 * lock (see README "Concurrency" for the full discussion of ordering
 * guarantees with and without async logging).
 */
public final class ConsoleAppender extends AbstractAppender {

    private final PrintStream out;

    public ConsoleAppender(String name, Layout layout, LogLevel threshold, PrintStream out) {
        super(name, layout, threshold);
        this.out = out != null ? out : System.out;
    }

    public ConsoleAppender(String name) {
        this(name, null, null, null);
    }

    @Override
    protected void doAppend(String formattedMessage, LogEvent event) {
        out.print(formattedMessage);
    }

    @Override
    public void flush() {
        out.flush();
    }

    @Override
    public void close() {
        flush();
        // Deliberately not closing System.out/err; only user-supplied custom
        // streams would need a close here, and this demo keeps it simple.
    }
}
