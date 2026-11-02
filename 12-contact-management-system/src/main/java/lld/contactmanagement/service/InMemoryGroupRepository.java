package lld.contactmanagement.service;

import lld.contactmanagement.model.Group;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link GroupRepository}. {@link Group} is a small immutable
 * value entity with no concurrent-edit hotspot (renames are rare), so a
 * plain {@link ConcurrentHashMap} with unconditional upsert is sufficient -
 * no CAS/optimistic-concurrency machinery is needed here, unlike
 * {@link InMemoryContactRepository}. This asymmetry is intentional: adding
 * the same complexity to both would be over-engineering for an entity that
 * doesn't need it.
 */
public final class InMemoryGroupRepository implements GroupRepository {

    private final ConcurrentHashMap<UUID, Group> store = new ConcurrentHashMap<>();

    @Override
    public void save(Group group) {
        store.put(group.id(), group);
    }

    @Override
    public Optional<Group> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Group> findByName(String name) {
        String normalized = name.trim().toLowerCase();
        return store.values().stream()
                .filter(g -> g.name().trim().toLowerCase().equals(normalized))
                .findFirst();
    }

    @Override
    public Collection<Group> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void delete(UUID id) {
        store.remove(id);
    }
}
