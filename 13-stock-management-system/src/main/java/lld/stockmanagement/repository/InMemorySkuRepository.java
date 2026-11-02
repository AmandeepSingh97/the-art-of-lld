package lld.stockmanagement.repository;

import lld.stockmanagement.exception.DuplicateSkuException;
import lld.stockmanagement.model.Sku;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link SkuRepository} backed by a {@link ConcurrentHashMap}.
 *
 * <p>Thread-safety: {@code ConcurrentHashMap} gives thread-safe reads/writes per key
 * without external locking. {@code putIfAbsent} is used for registration so
 * concurrent attempts to register the same SKU code race safely — exactly one wins
 * and the rest observe {@link DuplicateSkuException}, never a lost/overwritten entry.
 * The catalog changes far less often than inventory counts, so no write-heavy
 * contention is expected here; a simple concurrent map is sufficient and does not
 * need the strategy comparison applied to the inventory ledger.
 */
public final class InMemorySkuRepository implements SkuRepository {

    private final ConcurrentHashMap<String, Sku> skus = new ConcurrentHashMap<>();

    @Override
    public void register(Sku sku) {
        Sku existing = skus.putIfAbsent(sku.getSkuCode(), sku);
        if (existing != null) {
            throw new DuplicateSkuException(sku.getSkuCode());
        }
    }

    @Override
    public Optional<Sku> findByCode(String skuCode) {
        return Optional.ofNullable(skus.get(skuCode));
    }

    @Override
    public boolean exists(String skuCode) {
        return skus.containsKey(skuCode);
    }
}
