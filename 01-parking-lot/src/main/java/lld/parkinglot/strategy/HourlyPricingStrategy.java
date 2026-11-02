package lld.parkinglot.strategy;

import lld.parkinglot.enums.VehicleType;
import lld.parkinglot.model.Ticket;
import lld.parkinglot.util.Money;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bills per hour of stay, rounded up to the next whole hour with a
 * one-hour minimum (standard garage practice: 61 minutes bills as 2 hours).
 */
public final class HourlyPricingStrategy implements PricingStrategy {

    private final Map<VehicleType, Money> hourlyRateByType;

    public HourlyPricingStrategy(Map<VehicleType, Money> hourlyRateByType) {
        this.hourlyRateByType = new EnumMap<>(Objects.requireNonNull(hourlyRateByType, "hourlyRateByType"));
    }

    @Override
    public Money calculateFee(Ticket ticket, Instant exitTime) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(exitTime, "exitTime");
        if (exitTime.isBefore(ticket.getEntryTime())) {
            throw new IllegalArgumentException("exitTime cannot precede entryTime");
        }
        Money rate = hourlyRateByType.get(ticket.getVehicle().getType());
        if (rate == null) {
            throw new IllegalStateException("No hourly rate configured for " + ticket.getVehicle().getType());
        }
        Duration stayed = Duration.between(ticket.getEntryTime(), exitTime);
        long wholeHours = stayed.toMinutes() / 60;
        long remainderMinutes = stayed.toMinutes() % 60;
        long billableHours = Math.max(1, wholeHours + (remainderMinutes > 0 ? 1 : 0));
        return rate.multiply(billableHours);
    }
}
