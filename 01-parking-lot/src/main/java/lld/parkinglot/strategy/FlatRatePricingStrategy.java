package lld.parkinglot.strategy;

import lld.parkinglot.enums.VehicleType;
import lld.parkinglot.model.Ticket;
import lld.parkinglot.util.Money;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bills a single flat fee per visit regardless of duration - e.g. an event
 * venue or stadium lot that charges per-entry, not per-hour.
 */
public final class FlatRatePricingStrategy implements PricingStrategy {

    private final Map<VehicleType, Money> flatRateByType;

    public FlatRatePricingStrategy(Map<VehicleType, Money> flatRateByType) {
        this.flatRateByType = new EnumMap<>(Objects.requireNonNull(flatRateByType, "flatRateByType"));
    }

    @Override
    public Money calculateFee(Ticket ticket, Instant exitTime) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(exitTime, "exitTime");
        if (exitTime.isBefore(ticket.getEntryTime())) {
            throw new IllegalArgumentException("exitTime cannot precede entryTime");
        }
        Money rate = flatRateByType.get(ticket.getVehicle().getType());
        if (rate == null) {
            throw new IllegalStateException("No flat rate configured for " + ticket.getVehicle().getType());
        }
        return rate;
    }
}
