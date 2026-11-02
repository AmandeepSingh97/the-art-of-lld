package lld.stockmanagement.observer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory low-stock alerting listener. Fires a callback whenever a stock event
 * leaves {@code available} at or below a configured threshold for that key.
 *
 * <p>This is deliberately generic (a {@code Consumer<String>} sink) rather than
 * hard-wired to a logger or paging system, so the same listener works in both the
 * demo ({@code Main}) and tests (which can capture alerts into a list for
 * assertions). Thread-safety: the sink list is a {@link CopyOnWriteArrayList} and all
 * mutation of listener state is append-only, matching the read-mostly audit trail
 * rationale.
 */
public final class LowStockAlertListener implements StockEventListener {

    private final int lowStockThreshold;
    private final List<Consumer<String>> sinks = new CopyOnWriteArrayList<>();

    public LowStockAlertListener(int lowStockThreshold) {
        if (lowStockThreshold < 0) {
            throw new IllegalArgumentException("lowStockThreshold cannot be negative");
        }
        this.lowStockThreshold = lowStockThreshold;
    }

    public void addSink(Consumer<String> sink) {
        sinks.add(sink);
    }

    @Override
    public void onStockEvent(StockEvent event) {
        int available = event.getLevelAfter().getAvailable();
        if (available <= lowStockThreshold) {
            String message = "LOW STOCK ALERT: " + event.getInventoryKey()
                    + " available=" + available + " (threshold=" + lowStockThreshold + ")";
            for (Consumer<String> sink : sinks) {
                sink.accept(message);
            }
        }
    }
}
