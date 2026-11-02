package lld.parkinglot.strategy;

import lld.parkinglot.enums.SpotSize;
import lld.parkinglot.enums.VehicleType;
import lld.parkinglot.model.ParkingSpot;
import lld.parkinglot.model.Ticket;
import lld.parkinglot.model.Vehicle;
import lld.parkinglot.util.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HourlyPricingStrategyTest {

    private final HourlyPricingStrategy strategy = new HourlyPricingStrategy(
            Map.of(VehicleType.CAR, Money.of(2.0), VehicleType.MOTORCYCLE, Money.of(1.0)));

    private Ticket ticketEnteringAt(Instant entryTime, VehicleType type) {
        ParkingSpot spot = new ParkingSpot("S1", 0, 1, SpotSize.MEDIUM);
        spot.tryReserve();
        Vehicle vehicle = new Vehicle("KA-01-AA-0001", type);
        spot.occupy(vehicle);
        return new Ticket("T1", vehicle, spot, "GATE-1", entryTime);
    }

    @Test
    void chargesOneHourMinimumEvenForAVeryShortStay() {
        Instant entry = Instant.parse("2024-01-01T10:00:00Z");
        Ticket ticket = ticketEnteringAt(entry, VehicleType.CAR);
        Money fee = strategy.calculateFee(ticket, entry.plus(5, ChronoUnit.MINUTES));
        assertEquals(Money.of(2.0), fee);
    }

    @Test
    void exactlyOneHourChargesExactlyOneHour() {
        Instant entry = Instant.parse("2024-01-01T10:00:00Z");
        Ticket ticket = ticketEnteringAt(entry, VehicleType.CAR);
        Money fee = strategy.calculateFee(ticket, entry.plus(60, ChronoUnit.MINUTES));
        assertEquals(Money.of(2.0), fee);
    }

    @Test
    void oneMinuteOverAnHourRoundsUpToTwoHours() {
        Instant entry = Instant.parse("2024-01-01T10:00:00Z");
        Ticket ticket = ticketEnteringAt(entry, VehicleType.CAR);
        Money fee = strategy.calculateFee(ticket, entry.plus(61, ChronoUnit.MINUTES));
        assertEquals(Money.of(4.0), fee);
    }

    @Test
    void differentVehicleTypesUseDifferentRates() {
        Instant entry = Instant.parse("2024-01-01T10:00:00Z");
        Ticket ticket = ticketEnteringAt(entry, VehicleType.MOTORCYCLE);
        Money fee = strategy.calculateFee(ticket, entry.plus(2, ChronoUnit.HOURS));
        assertEquals(Money.of(2.0), fee);
    }

    @Test
    void exitTimeBeforeEntryTimeIsRejected() {
        Instant entry = Instant.parse("2024-01-01T10:00:00Z");
        Ticket ticket = ticketEnteringAt(entry, VehicleType.CAR);
        assertThrows(IllegalArgumentException.class,
                () -> strategy.calculateFee(ticket, entry.minus(1, ChronoUnit.MINUTES)));
    }

    @Test
    void missingRateForVehicleTypeIsRejected() {
        Instant entry = Instant.parse("2024-01-01T10:00:00Z");
        Ticket ticket = ticketEnteringAt(entry, VehicleType.BUS_OR_TRUCK);
        assertThrows(IllegalStateException.class, () -> strategy.calculateFee(ticket, entry.plus(1, ChronoUnit.HOURS)));
    }
}
