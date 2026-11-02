package lld.logging.appender;

import lld.logging.core.LogLevel;
import lld.logging.layout.Layout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;

/**
 * A {@link FileAppender} that rolls over to a new file once the current file
 * exceeds {@code maxBytes}. The current file is renamed with a timestamp
 * suffix (e.g. {@code app.log} -&gt; {@code app.log.2024-05-01T10-15-30.123Z})
 * and a fresh {@code app.log} is opened.
 *
 * <p>This is the simplest rollover policy that still demonstrates the core
 * idea; log4j/logback additionally support time-based rollover and a
 * maximum-file-count retention policy (both are called out as
 * straightforward extensions in the README's "Production Evolution"
 * section, intentionally left out here to keep the class focused).
 *
 * <p>Rollover decision-making reuses the parent's lock (via {@link
 * #afterWriteLocked()}), so "write a line" and "roll over if now too big"
 * are one atomic unit: no other thread can sneak a write into the old file
 * after the size threshold was crossed but before the rename happens.
 */
public final class RollingFileAppender extends FileAppender {

    private final long maxBytes;

    public RollingFileAppender(String name, Layout layout, LogLevel threshold, Path path, long maxBytes) {
        super(name, layout, threshold, path);
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    protected void afterWriteLocked() {
        if (bytesWrittenSinceOpenLocked() >= maxBytes) {
            rollOverLocked();
        }
    }

    private void rollOverLocked() {
        Path path = getPath();
        // Close the writer BEFORE renaming the file: on Windows, renaming a
        // file with an open handle fails; closing first keeps this portable.
        closeWriterLocked();
        try {
            Path rolled = path.resolveSibling(path.getFileName() + "." + Instant.now().toString().replace(':', '-'));
            if (Files.exists(path)) {
                Files.move(path, rolled, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new lld.logging.exception.AppenderException("Failed to roll over log file: " + path, e);
        } finally {
            // reopenLocked() closes (a no-op here, already closed) and opens
            // a fresh writer at the original path, resetting the byte count.
            reopenLocked();
        }
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}
