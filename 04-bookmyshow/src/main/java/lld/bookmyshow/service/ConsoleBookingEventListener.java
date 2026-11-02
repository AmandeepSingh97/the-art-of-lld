package lld.bookmyshow.service;

import lld.bookmyshow.model.Booking;

/** Simple console-logging listener used by the demo; stands in for a real notification service. */
public class ConsoleBookingEventListener implements BookingEventListener {
    @Override
    public void onBookingConfirmed(Booking booking) {
        System.out.println("[notify] Booking " + booking.getId() + " CONFIRMED for user " + booking.getUserId()
                + ", seats=" + booking.getSeatIds() + ", amount=" + booking.getAmount());
    }

    @Override
    public void onBookingCancelled(Booking booking) {
        System.out.println("[notify] Booking " + booking.getId() + " CANCELLED, refund issued for amount="
                + booking.getAmount());
    }

    @Override
    public void onBookingExpired(Booking booking) {
        System.out.println("[notify] Booking " + booking.getId() + " EXPIRED (seat hold TTL elapsed before payment)");
    }
}
