package lld.meetingscheduler.repository;

import lld.meetingscheduler.model.Meeting;
import lld.meetingscheduler.model.MeetingId;
import lld.meetingscheduler.model.UserId;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class InMemoryMeetingRepository implements MeetingRepository {

    private final Map<MeetingId, Meeting> store = new ConcurrentHashMap<>();

    @Override
    public Meeting save(Meeting meeting) {
        store.put(meeting.id(), meeting);
        return meeting;
    }

    @Override
    public Optional<Meeting> findById(MeetingId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Meeting> findByAttendee(UserId userId) {
        return store.values().stream()
                .filter(m -> m.attendeeIds().contains(userId))
                .collect(Collectors.toList());
    }
}
