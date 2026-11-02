package lld.splitwise.repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lld.splitwise.model.Group;

/**
 * In-memory implementation. Note {@link Group} itself is mutable (membership
 * can change), so unlike {@link InMemoryUserRepository} this repository does
 * not get thread-safety "for free" purely from the map being concurrent — it
 * additionally relies on {@link Group}'s own internal thread-safety
 * (a {@code CopyOnWriteArraySet} for membership) for concurrent member
 * add/remove while other threads read the member list.
 */
public final class InMemoryGroupRepository implements GroupRepository {

    private final Map<String, Group> groupsById = new ConcurrentHashMap<>();

    @Override
    public Group save(Group group) {
        groupsById.put(group.id(), group);
        return group;
    }

    @Override
    public Optional<Group> findById(String groupId) {
        return Optional.ofNullable(groupsById.get(groupId));
    }

    @Override
    public boolean existsById(String groupId) {
        return groupsById.containsKey(groupId);
    }
}
