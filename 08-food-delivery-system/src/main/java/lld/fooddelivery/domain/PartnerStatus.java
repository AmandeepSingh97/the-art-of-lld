package lld.fooddelivery.domain;

/**
 * Lifecycle status of a {@link DeliveryPartner}.
 *
 * <pre>
 * AVAILABLE --(reserved for an order)--&gt; BUSY --(delivery completed)--&gt; AVAILABLE
 * AVAILABLE --(goes offline)--&gt; OFFLINE --(comes back online)--&gt; AVAILABLE
 * </pre>
 */
public enum PartnerStatus {
    AVAILABLE,
    BUSY,
    OFFLINE
}
