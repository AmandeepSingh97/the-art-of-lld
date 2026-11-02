package lld.meetingscheduler.repository;

import lld.meetingscheduler.model.Room;
import lld.meetingscheduler.model.RoomId;

import java.util.Optional;

public interface RoomRepository {
    Room save(Room room);
    Optional<Room> findById(RoomId id);
    boolean exists(RoomId id);
}
