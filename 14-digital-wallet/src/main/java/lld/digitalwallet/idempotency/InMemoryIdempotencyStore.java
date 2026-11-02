package lld.digitalwallet.idempotency;

import lld.digitalwallet.model.Transaction;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory {@link IdempotencyStore} built directly on
 * {@link ConcurrentHashMap#computeIfAbsent}. The JDK guarantees that method
 * invocation is atomic per key - concurrent callers racing on the same key
 * block until the first caller's {@code computation} completes, then all of
 * them (including the first) observe the same return value. That is exactly
 * the "exactly-once side effect, idempotent result" contract we need, with
 * no extra locking code of our own.
 *
 * <p><b>Caveats (deliberately called out, this is a common interview
 * follow-up):</b>
 * <ul>
 *   <li>{@code computeIfAbsent}'s lambda must not attempt another
 *       operation on <em>this same map</em> (it would deadlock / throw
 *       {@link IllegalStateException} on some JDKs) - our computation only
 *       touches account/ledger/transaction repositories, never this map, so
 *       we are safe.</li>
 *   <li>A slow computation holds up any other thread that happens to hash
 *       to the same internal bin/key - acceptable for a wallet transfer
 *       (fast, in-process) but something a production Redis-backed
 *       implementation (SETNX + TTL, or a dedicated idempotency table with
 *       a unique constraint) would need to revisit under real load; see
 *       STAFF_LEVEL_DEEP_DIVE.md.</li>
 *   <li>This in-memory store has no TTL/eviction: in production, idempotency
 *       keys must expire (e.g. 24h) or the map grows unbounded.</li>
 * </ul>
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, Transaction> cache = new ConcurrentHashMap<>();

    @Override
    public Transaction computeIfAbsent(String idempotencyKey, Supplier<Transaction> computation) {
        return cache.computeIfAbsent(idempotencyKey, key -> computation.get());
    }
}
