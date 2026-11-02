package lld.fooddelivery.service;

import lld.fooddelivery.domain.DeliveryPartner;
import lld.fooddelivery.domain.GeoLocation;
import lld.fooddelivery.domain.Order;
import lld.fooddelivery.strategy.DeliveryPartnerAssignmentStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains the pool of {@link DeliveryPartner}s and assigns one to an {@link Order} using a
 * pluggable {@link DeliveryPartnerAssignmentStrategy} (Strategy pattern).
 *
 * <p><b>Concurrency — the two races this service must defend against:</b>
 * <ol>
 *   <li><b>Two orders racing for the same nearest partner.</b> The strategy only
 *       <em>ranks</em> candidates; the actual reservation is a
 *       {@link DeliveryPartner#tryReserve()} compare-and-set. If the CAS loses (another thread
 *       reserved the partner microseconds earlier), this service retries with the next-best
 *       candidate rather than failing outright.</li>
 *   <li><b>A partner is reserved for an order that is concurrently cancelled</b> (by the
 *       customer, by the restaurant backing out post-acceptance, or auto-cancelled on an
 *       acceptance timeout). After winning the CAS, this service calls
 *       {@link Order#assignDeliveryPartner(String)}, which itself is {@code synchronized} on
 *       the order and consults the order's current {@code OrderState}. If the order state no
 *       longer allows assignment (e.g. it was cancelled a moment earlier), the call returns
 *       {@code false} and this service <em>compensates</em> by releasing the partner back to
 *       {@code AVAILABLE} — the partner is never left stranded in {@code BUSY} for an order
 *       that will never use them.</li>
 * </ol>
 */
public final class DeliveryAssignmentService {

    private final ConcurrentHashMap<String, DeliveryPartner> partners = new ConcurrentHashMap<>();
    private final DeliveryPartnerAssignmentStrategy strategy;

    public DeliveryAssignmentService(DeliveryPartnerAssignmentStrategy strategy) {
        this.strategy = strategy;
    }

    public void registerPartner(DeliveryPartner partner) {
        partners.put(partner.id(), partner);
    }

    public Optional<DeliveryPartner> find(String partnerId) {
        return Optional.ofNullable(partners.get(partnerId));
    }

    /**
     * Attempts to find and reserve a delivery partner for {@code order}, retrying against
     * progressively narrowed candidate pools as reservation attempts lose races. Returns
     * {@link Optional#empty()} if no partner could be both selected and successfully attached
     * to the order (either none were available, or the order stopped accepting assignment
     * while this ran).
     */
    public Optional<DeliveryPartner> assignPartnerFor(Order order, GeoLocation pickupLocation) {
        List<DeliveryPartner> tried = new ArrayList<>();
        while (true) {
            List<DeliveryPartner> candidates = new ArrayList<>(partners.values());
            candidates.removeAll(tried);
            Optional<DeliveryPartner> candidateOpt = strategy.selectPartner(pickupLocation, candidates);
            if (candidateOpt.isEmpty()) {
                return Optional.empty();
            }
            DeliveryPartner candidate = candidateOpt.get();
            if (!candidate.tryReserve()) {
                // Lost the race for this partner to another concurrent assignment; try the next one.
                tried.add(candidate);
                continue;
            }
            if (order.assignDeliveryPartner(candidate.id())) {
                return Optional.of(candidate);
            }
            // Order no longer accepts an assignment (e.g. concurrently cancelled).
            // Compensate by releasing the reservation; no other partner would fare differently.
            candidate.release();
            return Optional.empty();
        }
    }

    /** Releases a partner back to the pool, e.g. after an order is cancelled post-assignment. */
    public void release(String partnerId) {
        DeliveryPartner partner = partners.get(partnerId);
        if (partner != null) {
            partner.release();
        }
    }
}
