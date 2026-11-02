package lld.fooddelivery.strategy;

import lld.fooddelivery.domain.DeliveryPartner;
import lld.fooddelivery.domain.GeoLocation;
import lld.fooddelivery.domain.PartnerStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Default production strategy: pick the {@code AVAILABLE} partner physically closest to the
 * restaurant's pickup location. Minimises rider travel time to pickup, which is the dominant
 * factor in "food arrives hot" outcomes for short-hop urban delivery.
 *
 * <p>O(n log n) over the candidate pool per call (a sort); for very large candidate pools a
 * spatial index (geohash/quad-tree/k-d tree) would be used instead of a linear scan — see
 * README "Data Structures and Algorithms" and "Performance and Scalability" for the trade-off
 * and when it is worth paying for that complexity.
 */
public final class NearestAvailablePartnerStrategy implements DeliveryPartnerAssignmentStrategy {

    @Override
    public Optional<DeliveryPartner> selectPartner(GeoLocation pickupLocation, List<DeliveryPartner> candidates) {
        return candidates.stream()
                .filter(p -> p.status() == PartnerStatus.AVAILABLE)
                .min(Comparator.comparingDouble(p -> p.currentLocation().distanceKmTo(pickupLocation)));
    }
}
