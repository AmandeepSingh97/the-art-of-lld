package lld.meetingscheduler.repository;

import lld.meetingscheduler.model.Room;
import lld.meetingscheduler.model.RoomId;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRoomRepository implements RoomRepository {

    private final Map<RoomId, Room> store = new ConcurrentHashMap<>();

    @Override
    public Room save(Room room) {
        store.put(room.id(), room);
        return room;
    }

    @Override
    public Optional<Room> findById(RoomId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public boolean exists(RoomId id) {
        return store.containsKey(id);
    }
}
