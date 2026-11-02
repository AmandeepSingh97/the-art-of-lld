package lld.meetingscheduler.repository;

import lld.meetingscheduler.model.Meeting;
import lld.meetingscheduler.model.MeetingId;

import java.util.List;
import java.util.Optional;

public interface MeetingRepository {
    Meeting save(Meeting meeting);
    Optional<Meeting> findById(MeetingId id);
    List<Meeting> findByAttendee(lld.meetingscheduler.model.UserId userId);
}
