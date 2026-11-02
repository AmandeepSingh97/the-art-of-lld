package lld.stockmanagement.model;

/**
 * Per-(SKU, warehouse) reorder configuration, used by the reorder-point automation
 * (see {@code lld.stockmanagement.observer.ReorderAutomationListener}).
 *
 * <p>When {@code available <= reorderPoint}, the system should raise a replenishment
 * suggestion for {@code reorderQuantity} units. This is a classic min/max inventory
 * policy — simple, explainable, and sufficient for interview scope; production systems
 * would instead compute the reorder point from lead-time demand forecasts, but that is
 * explicitly out of scope here (see README "Out of scope").
 */
public final class ReorderPolicy {

    private final int reorderPoint;
    private final int reorderQuantity;

    public ReorderPolicy(int reorderPoint, int reorderQuantity) {
        if (reorderPoint < 0) {
            throw new IllegalArgumentException("reorderPoint cannot be negative");
        }
        if (reorderQuantity <= 0) {
            throw new IllegalArgumentException("reorderQuantity must be positive");
        }
        this.reorderPoint = reorderPoint;
        this.reorderQuantity = reorderQuantity;
    }

    public int getReorderPoint() {
        return reorderPoint;
    }

    public int getReorderQuantity() {
        return reorderQuantity;
    }

    public boolean shouldReorder(int availableQuantity) {
        return availableQuantity <= reorderPoint;
    }
}
