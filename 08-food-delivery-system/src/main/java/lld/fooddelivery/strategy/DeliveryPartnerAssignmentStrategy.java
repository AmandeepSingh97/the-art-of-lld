package lld.fooddelivery.strategy;

import lld.fooddelivery.domain.DeliveryPartner;
import lld.fooddelivery.domain.GeoLocation;

import java.util.List;
import java.util.Optional;

/**
 * Strategy pattern for choosing which delivery partner to offer an order to first.
 * Decoupling this from {@link lld.fooddelivery.service.DeliveryAssignmentService} lets the
 * business swap assignment policy (nearest-available today, a load-balanced or
 * earnings-fairness policy tomorrow) without touching the reservation/compensation logic that
 * surrounds it.
 *
 * <p>Implementations must be side-effect-free with respect to partner state — they only
 * <em>rank/select</em> a candidate; the caller is responsible for the actual atomic
 * reservation ({@link DeliveryPartner#tryReserve()}), because only the caller can retry the
 * next candidate if the first one loses the reservation race.
 */
public interface DeliveryPartnerAssignmentStrategy {
    Optional<DeliveryPartner> selectPartner(GeoLocation pickupLocation, List<DeliveryPartner> candidates);
}
