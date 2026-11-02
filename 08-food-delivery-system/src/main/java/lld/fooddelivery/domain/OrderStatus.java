package lld.fooddelivery.domain;

/**
 * The full set of statuses an {@link Order} can occupy. The allowed transitions between these
 * statuses are enforced by the {@code lld.fooddelivery.state} package (State pattern), not by
 * this enum — this enum is intentionally "dumb" data so that it stays a trivial, stable
 * serialization format while the transition logic (which changes more often as business rules
 * evolve) lives in one place per state.
 *
 * <pre>
 *  PLACED --accept--&gt; ACCEPTED --startPreparing--&gt; PREPARING --markReady--&gt; READY_FOR_PICKUP
 *     |                   |                                                      |
 *     reject            cancel (not after PREPARING)                          pickUp
 *     |                   |                                                      |
 *     v                   v                                                      v
 *  REJECTED           CANCELLED                                           PICKED_UP --deliver--&gt; DELIVERED
 * </pre>
 */
public enum OrderStatus {
    PLACED,
    ACCEPTED,
    REJECTED,
    PREPARING,
    READY_FOR_PICKUP,
    PICKED_UP,
    DELIVERED,
    CANCELLED;

    public boolean isTerminal() {
        return this == REJECTED || this == DELIVERED || this == CANCELLED;
    }
}
