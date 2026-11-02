package lld.fooddelivery;

import lld.fooddelivery.domain.DeliveryPartner;
import lld.fooddelivery.domain.GeoLocation;
import lld.fooddelivery.domain.PartnerStatus;
import lld.fooddelivery.strategy.DeliveryPartnerAssignmentStrategy;
import lld.fooddelivery.strategy.NearestAvailablePartnerStrategy;
import lld.fooddelivery.strategy.RoundRobinPartnerStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validates both {@link DeliveryPartnerAssignmentStrategy} implementations in isolation. */
class DeliveryAssignmentStrategyTest {

    @Test
    void nearestAvailableStrategyPicksClosestAvailablePartner() {
        GeoLocation pickup = new GeoLocation(12.9716, 77.5946);
        DeliveryPartner near = new DeliveryPartner("rider-near", "Near",
                new GeoLocation(12.9717, 77.5947), PartnerStatus.AVAILABLE);
        DeliveryPartner far = new DeliveryPartner("rider-far", "Far",
                new GeoLocation(13.2, 77.9), PartnerStatus.AVAILABLE);

        Optional<DeliveryPartner> result =
                new NearestAvailablePartnerStrategy().selectPartner(pickup, List.of(far, near));

        assertEquals("rider-near", result.orElseThrow().id());
    }

    @Test
    void nearestAvailableStrategyIgnoresBusyAndOfflinePartners() {
        GeoLocation pickup = new GeoLocation(12.9716, 77.5946);
        DeliveryPartner busyButCloser = new DeliveryPartner("rider-busy", "Busy",
                new GeoLocation(12.9717, 77.5947), PartnerStatus.BUSY);
        DeliveryPartner offlineButCloser = new DeliveryPartner("rider-offline", "Offline",
                new GeoLocation(12.9717, 77.5947), PartnerStatus.OFFLINE);
        DeliveryPartner available = new DeliveryPartner("rider-avail", "Available",
                new GeoLocation(13.0, 78.0), PartnerStatus.AVAILABLE);

        Optional<DeliveryPartner> result = new NearestAvailablePartnerStrategy()
                .selectPartner(pickup, List.of(busyButCloser, offlineButCloser, available));

        assertEquals("rider-avail", result.orElseThrow().id());
    }

    @Test
    void nearestAvailableStrategyReturnsEmptyWhenNoneAvailable() {
        GeoLocation pickup = new GeoLocation(0, 0);
        DeliveryPartner busy = new DeliveryPartner("rider-1", "Busy", pickup, PartnerStatus.BUSY);
        assertTrue(new NearestAvailablePartnerStrategy().selectPartner(pickup, List.of(busy)).isEmpty());
    }

    @Test
    void roundRobinStrategyCyclesThroughAvailablePartners() {
        GeoLocation pickup = new GeoLocation(0, 0);
        DeliveryPartner a = new DeliveryPartner("a", "A", pickup, PartnerStatus.AVAILABLE);
        DeliveryPartner b = new DeliveryPartner("b", "B", pickup, PartnerStatus.AVAILABLE);
        RoundRobinPartnerStrategy strategy = new RoundRobinPartnerStrategy();
        List<DeliveryPartner> candidates = List.of(a, b);

        String first = strategy.selectPartner(pickup, candidates).orElseThrow().id();
        String second = strategy.selectPartner(pickup, candidates).orElseThrow().id();
        String third = strategy.selectPartner(pickup, candidates).orElseThrow().id();

        assertEquals(first, third, "the cursor should wrap back around after cycling through all candidates");
        assertTrue(!first.equals(second), "consecutive selections should round-robin, not repeat the same partner");
    }
}
