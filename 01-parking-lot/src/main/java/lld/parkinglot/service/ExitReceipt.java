package lld.parkinglot.service;

import lld.parkinglot.model.Payment;
import lld.parkinglot.model.Ticket;

import java.util.Objects;

/** Immutable summary handed back to the exit gate / driver once a stay is fully settled. */
public final class ExitReceipt {

    private final Ticket ticket;
    private final Payment payment;

    public ExitReceipt(Ticket ticket, Payment payment) {
        this.ticket = Objects.requireNonNull(ticket, "ticket");
        this.payment = Objects.requireNonNull(payment, "payment");
    }

    public Ticket getTicket() {
        return ticket;
    }

    public Payment getPayment() {
        return payment;
    }

    @Override
    public String toString() {
        return "Receipt[ticket=" + ticket.getId() + ", vehicle=" + ticket.getVehicle().getLicensePlate()
                + ", charged=" + payment.getAmount() + ", method=" + payment.getMethod() + "]";
    }
}
