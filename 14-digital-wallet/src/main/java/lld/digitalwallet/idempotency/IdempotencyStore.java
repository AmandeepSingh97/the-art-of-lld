package lld.digitalwallet.idempotency;

import lld.digitalwallet.model.Transaction;

import java.util.function.Supplier;

/**
 * Guarantees that a client-supplied idempotency key maps to at most one
 * executed side-effect, even when two threads race to submit a request with
 * the same key (e.g. a mobile client retrying after a timed-out response,
 * racing its own earlier attempt that actually succeeded).
 *
 * <p>The contract is: {@link #computeIfAbsent} executes {@code computation}
 * <em>at most once</em> per key, atomically with respect to concurrent
 * callers using the same key - the "won" caller runs the real transfer logic
 * and all other concurrent (and later) callers with that key receive the
 * exact same cached {@link Transaction} result without re-executing any
 * side effect. If {@code computation} throws, no result is cached, so a
 * failed attempt (e.g. insufficient funds at that moment) can legitimately
 * be retried with the same key later - this is intentional: we only want to
 * dedupe *successful* side effects, not a client's legitimate retry-after-
 * failure.
 */
public interface IdempotencyStore {

    Transaction computeIfAbsent(String idempotencyKey, Supplier<Transaction> computation);
}
