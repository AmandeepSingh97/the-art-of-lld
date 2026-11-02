package lld.logging.demo;

import lld.logging.appender.AsyncAppender;
import lld.logging.appender.BackpressurePolicy;
import lld.logging.appender.ConsoleAppender;
import lld.logging.appender.RollingFileAppender;
import lld.logging.config.LoggingConfiguration;
import lld.logging.core.LogLevel;
import lld.logging.core.Logger;
import lld.logging.core.LoggerContext;
import lld.logging.layout.PatternLayout;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runnable demonstration wiring the framework together end-to-end:
 * <ul>
 *   <li>ROOT logger -&gt; synchronous {@link ConsoleAppender} (everything INFO+).</li>
 *   <li>{@code "com.app.payment"} logger -&gt; DEBUG level, non-additive, routed
 *       only to an {@link AsyncAppender} wrapping a {@link RollingFileAppender}
 *       (so console stays uncluttered with payment debug noise).</li>
 * </ul>
 * Then logs concurrently from several threads to show thread-safety and
 * ordering behavior, and finally shuts the context down to demonstrate
 * graceful async flush.
 *
 * <p>Run with: {@code java lld.logging.demo.LoggingDemo}
 */
public final class LoggingDemo {

    public static void main(String[] args) throws Exception {
        Path logFile = Path.of("build", "demo-logs", "payment.log");

        ConsoleAppender console = new ConsoleAppender(
                "console", new PatternLayout("%d %p [%t] %c - %m%n"), LogLevel.INFO, System.out);

        RollingFileAppender rollingFile = new RollingFileAppender(
                "payment-file", new PatternLayout(), LogLevel.TRACE, logFile, 64 * 1024);
        AsyncAppender asyncFile = new AsyncAppender(
                "async-payment-file", rollingFile, /*queueCapacity*/ 1024, BackpressurePolicy.BLOCK);

        LoggingConfiguration configuration = LoggingConfiguration.builder()
                .rootLevel(LogLevel.INFO)
                .addRootAppender(console)
                .forLogger("com.app.payment")
                    .level(LogLevel.DEBUG)
                    .additive(false)
                    .addAppender(asyncFile)
                    .and()
                .build();

        LoggerContext context = new LoggerContext();
        context.configure(configuration);

        Logger appLogger = context.getLogger("com.app.Main");
        Logger paymentLogger = context.getLogger("com.app.payment.PaymentService");

        appLogger.info("Application starting up");

        int threadCount = 8;
        int messagesPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < messagesPerThread; i++) {
                        paymentLogger.debug("Processing payment " + threadId + "-" + i);
                        if (i % 100 == 0) {
                            appLogger.info("Heartbeat from worker " + threadId + " at message " + i);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        appLogger.info("Application shutting down; dropped async events = " + asyncFile.getDroppedEventCount());

        // Graceful shutdown: flushes and closes every appender reachable in
        // the hierarchy, including draining the AsyncAppender's queue.
        context.shutdown();
    }
}
