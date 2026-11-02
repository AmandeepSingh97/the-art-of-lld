package lld.bookmyshow.service;

import lld.bookmyshow.model.Booking;

/**
 * Observer interface for booking lifecycle events. Decouples
 * {@code BookingService} from downstream concerns like notifications,
 * analytics, or cache invalidation — those can each register a listener
 * without BookingService knowing they exist.
 */
public interface BookingEventListener {
    void onBookingConfirmed(Booking booking);

    void onBookingCancelled(Booking booking);

    void onBookingExpired(Booking booking);
}
