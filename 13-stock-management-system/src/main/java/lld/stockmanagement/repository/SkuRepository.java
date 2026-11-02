package lld.stockmanagement.repository;

import lld.stockmanagement.model.Sku;
import java.util.Optional;

/**
 * Catalog abstraction for SKUs. Kept deliberately narrow (register/find) — this is a
 * Repository pattern boundary so {@code InventoryService} does not know whether SKUs
 * live in a {@link java.util.Map} (as in this exercise) or a real database table in
 * production. Swapping the implementation requires no change above this interface.
 */
public interface SkuRepository {

    void register(Sku sku);

    Optional<Sku> findByCode(String skuCode);

    boolean exists(String skuCode);
}
