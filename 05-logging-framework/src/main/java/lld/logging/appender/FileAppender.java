package lld.logging.appender;

import lld.logging.core.LogEvent;
import lld.logging.core.LogLevel;
import lld.logging.exception.AppenderException;
import lld.logging.layout.Layout;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Appends formatted log lines to a file on disk, using a buffered writer.
 *
 * <p><b>Thread-safety:</b> unlike {@link ConsoleAppender} (which piggybacks
 * on {@link java.io.PrintStream}'s own synchronization), a {@link
 * BufferedWriter} is <em>not</em> thread-safe, and we also need to
 * atomically pair "write" with "maybe flush" with "maybe roll over" (in the
 * {@link RollingFileAppender} subclass) -- a compound action that must be
 * indivisible. We therefore guard the write path with an explicit {@link
 * ReentrantLock}. A lock (rather than a {@code synchronized} block) is used
 * so that subclasses can, if ever needed, use {@code tryLock}/timed
 * acquisition for backpressure instead of blocking forever; the base class
 * here always does a plain blocking {@code lock()} for simplicity, which is
 * correct and sufficient for the interview scope.
 *
 * <p><b>Buffering/flush semantics:</b> by default every event is flushed
 * immediately after being written (durability favoured over throughput,
 * appropriate for a general-purpose appender used directly by application
 * threads). When this appender sits behind an {@link AsyncAppender}, the
 * extra flush cost is paid by the background thread, not the caller, so the
 * default is safe to keep even under load; {@link #setImmediateFlush}
 * allows disabling it for appenders where the caller explicitly wants to
 * batch writes (e.g. a periodic {@code flush()} is scheduled elsewhere).
 */
public class FileAppender extends AbstractAppender {

    private final Path path;
    private volatile boolean immediateFlush = true;
    private final ReentrantLock lock = new ReentrantLock();
    private BufferedWriter writer;
    private long bytesWrittenSinceOpen;

    public FileAppender(String name, Layout layout, LogLevel threshold, Path path) {
        super(name, layout, threshold);
        this.path = Objects.requireNonNull(path, "path");
        openWriter();
    }

    public void setImmediateFlush(boolean immediateFlush) {
        this.immediateFlush = immediateFlush;
    }

    public Path getPath() {
        return path;
    }

    private void openWriter() {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                    StandardCharsets.UTF_8));
            this.bytesWrittenSinceOpen = Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            throw new AppenderException("Failed to open log file: " + path, e);
        }
    }

    @Override
    protected final void doAppend(String formattedMessage, LogEvent event) {
        lock.lock();
        try {
            writeLocked(formattedMessage);
            // Hook for subclasses (e.g. RollingFileAppender) that need to
            // inspect state (like file size) and react (roll over) as part
            // of the SAME critical section as the write, so no other
            // thread's write can be interleaved between "write" and "the
            // decision whether to roll".
            afterWriteLocked();
        } finally {
            lock.unlock();
        }
    }

    /** Executes under {@link #lock}, immediately after a successful write. */
    protected void afterWriteLocked() {
        // no-op by default
    }

    /**
     * Executes under {@link #lock}. Package-private extension point so
     * {@link RollingFileAppender} can perform the "write, then check size,
     * then maybe roll" sequence as one atomic critical section rather than
     * re-acquiring the lock (which would allow another thread's write to be
     * interleaved between the write and the roll-over check).
     */
    void writeLocked(String formattedMessage) {
        try {
            writer.write(formattedMessage);
            bytesWrittenSinceOpen += formattedMessage.getBytes(StandardCharsets.UTF_8).length;
            if (immediateFlush) {
                writer.flush();
            }
        } catch (IOException e) {
            throw new AppenderException("Failed to write to log file: " + path, e);
        }
    }

    long bytesWrittenSinceOpenLocked() {
        return bytesWrittenSinceOpen;
    }

    /** Closes the current writer only, without reopening. Executes under {@link #lock}. */
    void closeWriterLocked() {
        closeWriterQuietly();
    }

    void reopenLocked() {
        closeWriterQuietly();
        bytesWrittenSinceOpen = 0;
        openWriter();
    }

    final ReentrantLock lockObject() {
        return lock;
    }

    private void closeWriterQuietly() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
            // Best-effort close; a failure here does not prevent reopening.
        }
    }

    @Override
    public void flush() {
        lock.lock();
        try {
            writer.flush();
        } catch (IOException e) {
            throw new AppenderException("Failed to flush log file: " + path, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            throw new AppenderException("Failed to close log file: " + path, e);
        } finally {
            lock.unlock();
        }
    }
}
