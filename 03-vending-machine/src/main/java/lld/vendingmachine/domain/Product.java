package lld.vendingmachine.domain;

import java.util.Objects;

/**
 * Value object describing a sellable item: a stable catalog identifier, a
 * human-readable name, and its current price. Products are intentionally
 * decoupled from {@link Slot} — a product describes *what* is sold, a slot
 * describes *where* and *how many* units physically sit in the machine. This
 * separation lets the same product (e.g. "Coke 330ml") occupy more than one
 * slot, and lets an admin reprice a product without touching inventory counts.
 */
public final class Product {

    private final String productId;
    private final String name;
    private volatile Money price;

    public Product(String productId, String name, Money price) {
        this.productId = Objects.requireNonNull(productId, "productId");
        this.name = Objects.requireNonNull(name, "name");
        this.price = Objects.requireNonNull(price, "price");
    }

    public String getProductId() {
        return productId;
    }

    public String getName() {
        return name;
    }

    public Money getPrice() {
        return price;
    }

    /**
     * Admin-only operation to reprice a product. {@code volatile} gives us a
     * safe-publication guarantee: a price change made by an admin thread is
     * immediately visible to a customer thread reading the price next, without
     * needing a full lock, because price updates are independent, single-field,
     * last-writer-wins operations with no invariant linking them to other state.
     */
    public void repriceTo(Money newPrice) {
        this.price = Objects.requireNonNull(newPrice, "newPrice");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Product product)) return false;
        return productId.equals(product.productId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productId);
    }

    @Override
    public String toString() {
        return name + " (" + productId + ") @ " + price;
    }
}
