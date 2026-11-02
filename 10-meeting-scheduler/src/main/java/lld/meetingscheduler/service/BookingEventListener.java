package lld.meetingscheduler.service;

import lld.meetingscheduler.model.Meeting;
import lld.meetingscheduler.model.TimeInterval;

/**
 * Observer pattern: decouples "a booking/cancellation happened" from "what to do about it"
 * (send an email, push a mobile notification, emit a metric). {@link SchedulerService} only
 * knows how to publish events to registered listeners; it has zero knowledge of notification
 * channels, which keeps it honest about its one responsibility (scheduling) rather than
 * growing into a god class that also knows about email templates and push tokens.
 */
public interface BookingEventListener {
    void onMeetingBooked(Meeting meeting);
    void onMeetingCancelled(Meeting meeting, TimeInterval cancelledOccurrence);
}
