package lld.logging.appender;

import lld.logging.core.LogLevel;
import lld.logging.layout.SimpleLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollingFileAppenderTest {

    // Deliberately NOT using the JVM default temp directory: scratch files
    // for this test live under the project's own build/ output directory
    // and are removed in @AfterEach.
    private Path testDir;

    @BeforeEach
    void setUp() throws IOException {
        testDir = Path.of("build", "test-scratch", UUID.randomUUID().toString());
        Files.createDirectories(testDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> walk = Files.walk(testDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void writesAllLinesToTheActiveFileWhenUnderSizeThreshold() {
        Path path = testDir.resolve("app.log");
        lld.logging.core.LogEvent event = new lld.logging.core.LogEvent("x", LogLevel.INFO, "hello", null);
        try (RollingFileAppender appender = new RollingFileAppender(
                "f", new SimpleLayout(), LogLevel.TRACE, path, 1_000_000)) {
            for (int i = 0; i < 10; i++) {
                appender.append(event);
            }
        }

        assertTrue(Files.exists(path));
        long lineCount = countLines(path);
        assertEquals(10, lineCount);
    }

    @Test
    void rollsOverToANewFileOnceSizeThresholdIsExceeded() throws IOException {
        Path path = testDir.resolve("app.log");
        // Each written line is well over 20 bytes, so a 100-byte threshold
        // forces a rollover after just a few writes.
        lld.logging.core.LogEvent event =
                new lld.logging.core.LogEvent("x", LogLevel.INFO, "0123456789", null);
        try (RollingFileAppender appender = new RollingFileAppender(
                "f", new SimpleLayout(), LogLevel.TRACE, path, 100)) {
            for (int i = 0; i < 20; i++) {
                appender.append(event);
            }
        }

        List<Path> rolledFiles;
        try (Stream<Path> list = Files.list(testDir)) {
            rolledFiles = list.filter(p -> p.getFileName().toString().startsWith("app.log.")).toList();
        }

        assertTrue(rolledFiles.size() >= 1, "expected at least one rolled-over file, found: " + rolledFiles);
        assertTrue(Files.exists(path), "the active file should still exist after rollover");
    }

    private static long countLines(Path path) {
        try {
            return Files.readAllLines(path).size();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
