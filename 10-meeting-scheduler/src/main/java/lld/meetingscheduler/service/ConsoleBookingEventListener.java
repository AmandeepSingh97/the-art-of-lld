package lld.meetingscheduler.service;

import lld.meetingscheduler.model.Meeting;
import lld.meetingscheduler.model.TimeInterval;

/** Minimal listener used by the demo/tests; a production system would have an email/push
 *  implementation instead (or in addition) -- see README §17. */
public final class ConsoleBookingEventListener implements BookingEventListener {

    @Override
    public void onMeetingBooked(Meeting meeting) {
        System.out.println("[notify] Meeting booked: " + meeting.title() + " (" + meeting.id() + ")");
    }

    @Override
    public void onMeetingCancelled(Meeting meeting, TimeInterval cancelledOccurrence) {
        System.out.println("[notify] Occurrence cancelled on '" + meeting.title() + "': " + cancelledOccurrence);
    }
}
