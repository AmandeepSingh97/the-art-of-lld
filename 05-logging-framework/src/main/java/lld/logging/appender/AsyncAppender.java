package lld.logging.appender;

import lld.logging.core.LogEvent;
import lld.logging.core.LogLevel;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorator that turns any synchronous {@link Appender} into an
 * asynchronous one, using the classic producer/consumer pattern: application
 * threads (producers) enqueue {@link LogEvent}s onto a bounded {@link
 * BlockingQueue}; a single dedicated background thread (the consumer) drains
 * the queue and forwards each event to the wrapped {@code delegate}
 * appender.
 *
 * <h2>Ordering guarantee</h2>
 * Exactly one consumer thread ever calls {@code delegate.append(...)}, and
 * it always processes events in the order they successfully entered the
 * queue (a {@link BlockingQueue} is FIFO). This gives us:
 * <ul>
 *   <li><b>Per-producer-thread ordering is preserved</b>: if thread A logs
 *       event e1 then e2, e1 enters the queue before e2 (A executes its own
 *       calls sequentially), so e1 is written before e2.</li>
 *   <li><b>Cross-thread ordering is NOT guaranteed</b>: if threads A and B
 *       log concurrently, whichever of their {@code put}/{@code offer}
 *       calls happens to complete first determines queue position -- this
 *       is identical to the ordering (non-)guarantee of log4j's own
 *       AsyncAppender/Disruptor-based appenders and is called out explicitly
 *       in the README so it is never mis-sold as "global total order".</li>
 *   <li>Having a single consumer also means the delegate appender never
 *       needs to be thread-safe against concurrent writers when used purely
 *       behind this decorator -- but we still make {@link FileAppender}
 *       thread-safe directly, since it may also be attached, unwrapped, to
 *       multiple loggers/threads at once.</li>
 * </ul>
 *
 * <h2>Backpressure</h2>
 * The queue is bounded (to bound memory use when the delegate falls behind,
 * e.g. slow disk). What happens when it's full is pluggable via {@link
 * BackpressurePolicy}; see that enum for the trade-offs of each choice.
 *
 * <h2>Graceful shutdown</h2>
 * {@link #close()} stops accepting new events, enqueues a poison-pill
 * sentinel, and waits (bounded by {@code shutdownTimeout}) for the consumer
 * thread to drain everything already queued and exit, before closing the
 * delegate. This guarantees that every event accepted before {@code close()}
 * was called is flushed to the delegate -- important so that, e.g., a JVM
 * shutdown hook can log "shutting down" and trust it actually reaches disk.
 */
public final class AsyncAppender implements Appender {

    private static final LogEvent SHUTDOWN_SENTINEL =
            new LogEvent("__internal__", LogLevel.OFF, "__shutdown_sentinel__", null);

    private final String name;
    private final Appender delegate;
    private final BlockingQueue<LogEvent> queue;
    private final BackpressurePolicy backpressurePolicy;
    private final Thread consumerThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong droppedEventCount = new AtomicLong();

    public AsyncAppender(String name, Appender delegate, int queueCapacity, BackpressurePolicy backpressurePolicy) {
        this.name = Objects.requireNonNull(name, "name");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.backpressurePolicy = Objects.requireNonNull(backpressurePolicy, "backpressurePolicy");
        this.consumerThread = new Thread(this::consumeLoop, "async-appender-" + name);
        // Daemon=false on purpose: we want the JVM to wait for a final flush
        // during a normal shutdown-hook-driven close(); a daemon thread
        // could be killed mid-write. The onus is on the application to call
        // close()/shutdown explicitly (see LoggerContext.shutdown()).
        this.consumerThread.setDaemon(false);
        this.consumerThread.start();
    }

    @Override
    public String getName() {
        return name;
    }

    public long getDroppedEventCount() {
        return droppedEventCount.get();
    }

    public int getQueueSize() {
        return queue.size();
    }

    @Override
    public void append(LogEvent event) {
        if (!running.get()) {
            // Appender is shutting down/shut down: fail safe by dropping
            // rather than throwing, so a straggling log call during shutdown
            // never crashes the caller.
            droppedEventCount.incrementAndGet();
            return;
        }
        switch (backpressurePolicy) {
            case BLOCK -> enqueueBlocking(event);
            case DROP_NEW -> enqueueDropNew(event);
            case DISCARD_OLDEST -> enqueueDiscardOldest(event);
        }
    }

    private void enqueueBlocking(LogEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            droppedEventCount.incrementAndGet();
        }
    }

    private void enqueueDropNew(LogEvent event) {
        boolean accepted = queue.offer(event);
        if (!accepted) {
            droppedEventCount.incrementAndGet();
        }
    }

    private void enqueueDiscardOldest(LogEvent event) {
        // Best-effort: under concurrent producers this can race (two
        // producers may both observe "full" and each discard one head
        // element), which can over-discard by a small amount under heavy
        // concurrent contention. That is an accepted imprecision for this
        // policy -- it trades strict precision for avoiding a second,
        // coarser lock around the whole discard+offer sequence, which would
        // reintroduce the very contention async logging exists to avoid.
        if (!queue.offer(event)) {
            queue.poll(); // evict oldest, if any remain after the race above
            if (!queue.offer(event)) {
                // Queue was refilled by another producer between poll() and
                // offer(); fall back to dropping the new event rather than
                // looping unboundedly.
                droppedEventCount.incrementAndGet();
            }
        }
    }

    private void consumeLoop() {
        while (true) {
            LogEvent event;
            try {
                event = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (event == SHUTDOWN_SENTINEL) {
                break;
            }
            try {
                delegate.append(event);
            } catch (RuntimeException e) {
                // A single bad event/delegate failure must not kill the
                // consumer thread -- that would silently stop all future
                // logging for this appender. Swallow-and-continue is the
                // right call here; a production system would additionally
                // emit a metric (see README "Production Evolution").
            }
        }
    }

    @Override
    public void flush() {
        // Flushing the delegate is meaningful even mid-run (e.g. a periodic
        // caller wants recent events on disk); it does not drain the queue,
        // it only flushes whatever the delegate has already buffered.
        delegate.flush();
    }

    /** Equivalent to {@code close(Duration.ofSeconds(10))}. */
    @Override
    public void close() {
        close(10_000);
    }

    /**
     * Stops accepting new events, waits up to {@code timeoutMillis} for the
     * consumer thread to drain the queue and exit, then closes the delegate.
     * If the timeout elapses first, remaining queued events are logged as
     * dropped (counted) rather than blocking shutdown forever.
     */
    public void close(long timeoutMillis) {
        if (!running.compareAndSet(true, false)) {
            return; // already closed; close() is idempotent
        }
        try {
            // Blocking put (not offer) for the sentinel: we need it to be
            // guaranteed to enter the queue (after any already-queued real
            // events) so the consumer thread is guaranteed to see it and
            // exit, rather than silently never terminating because the
            // queue was briefly full.
            queue.put(SHUTDOWN_SENTINEL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            consumerThread.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (consumerThread.isAlive()) {
            droppedEventCount.addAndGet(queue.size());
            consumerThread.interrupt();
        }
        delegate.close();
    }
}
