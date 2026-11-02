package lld.fooddelivery.strategy;

import lld.fooddelivery.domain.DeliveryPartner;
import lld.fooddelivery.domain.GeoLocation;
import lld.fooddelivery.domain.PartnerStatus;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Alternative strategy considered: cycle through available partners in turn instead of always
 * picking the nearest one. This optimises for <em>fairness of earnings distribution</em> among
 * riders rather than customer delivery-time, which is a real business lever (rider retention)
 * some platforms dial up during off-peak hours. Included to demonstrate that the Strategy
 * pattern pays for itself the moment a second real policy exists — see README "Design
 * Patterns" for why nearest-available was chosen as the default over this one.
 *
 * <p>{@link #cursor} is an {@link AtomicInteger} because this strategy object may be shared
 * and invoked concurrently by multiple assignment threads; a plain {@code int} field would
 * lose increments under concurrent access.
 */
public final class RoundRobinPartnerStrategy implements DeliveryPartnerAssignmentStrategy {

    private final AtomicInteger cursor = new AtomicInteger(0);

    @Override
    public Optional<DeliveryPartner> selectPartner(GeoLocation pickupLocation, List<DeliveryPartner> candidates) {
        List<DeliveryPartner> available = candidates.stream()
                .filter(p -> p.status() == PartnerStatus.AVAILABLE)
                .toList();
        if (available.isEmpty()) {
            return Optional.empty();
        }
        int index = Math.floorMod(cursor.getAndIncrement(), available.size());
        return Optional.of(available.get(index));
    }
}
