package lld.parkinglot.strategy;

import lld.parkinglot.model.Ticket;
import lld.parkinglot.util.Money;

import java.time.Instant;

/**
 * Strategy Pattern: computes the fee owed for a completed stay. Separating
 * this from {@code Ticket}/{@code ExitGateService} lets the lot switch
 * between hourly and flat pricing (or add weekday/weekend, surge, or
 * membership pricing later) without touching ticket lifecycle or payment
 * code - classic Open/Closed.
 */
public interface PricingStrategy {

    /**
     * @param ticket   the active ticket being closed (entryTime, vehicle type available)
     * @param exitTime the instant the vehicle is leaving
     * @return the fee owed, never negative
     */
    Money calculateFee(Ticket ticket, Instant exitTime);
}
