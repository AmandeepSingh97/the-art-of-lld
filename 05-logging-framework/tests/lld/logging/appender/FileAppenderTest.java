package lld.logging.appender;

import lld.logging.core.LogEvent;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAppenderTest {

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
    void concurrentWritersFromMultipleThreadsProduceNoCorruptedOrInterleavedLines() throws Exception {
        Path path = testDir.resolve("concurrent.log");
        int threadCount = 16;
        int messagesPerThread = 200;

        try (FileAppender appender = new FileAppender("f", new SimpleLayout(), LogLevel.TRACE, path)) {
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < messagesPerThread; i++) {
                            appender.append(new LogEvent(
                                    "thread-" + threadId, LogLevel.INFO, "payload-" + threadId + "-" + i, null));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(30, TimeUnit.SECONDS));
            pool.shutdown();
        }

        List<String> lines = Files.readAllLines(path);
        assertEquals(threadCount * messagesPerThread, lines.size(),
                "every line written by every thread must land in the file with no loss or corruption");

        // Every line must be a single, complete, well-formed record -- i.e.
        // no two concurrent writes were torn and interleaved into one line.
        for (String line : lines) {
            assertTrue(line.matches("INFO \\[.*] thread-\\d+ - payload-\\d+-\\d+"),
                    "line was corrupted/interleaved by a concurrent writer: " + line);
        }
    }
}
