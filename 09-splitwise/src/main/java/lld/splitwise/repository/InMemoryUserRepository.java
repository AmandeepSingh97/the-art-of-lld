package lld.splitwise.repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lld.splitwise.model.User;

/**
 * In-memory implementation backed by {@link ConcurrentHashMap}. Safe for
 * concurrent reads/writes: individual map operations (put/get/containsKey)
 * are atomic, and {@link User} itself is immutable, so once published into
 * the map a {@code User} instance can be freely read by any thread without
 * extra synchronization (safe publication via a concurrent map's
 * happens-before guarantees).
 */
public final class InMemoryUserRepository implements UserRepository {

    private final Map<String, User> usersById = new ConcurrentHashMap<>();

    @Override
    public User save(User user) {
        usersById.put(user.id(), user);
        return user;
    }

    @Override
    public Optional<User> findById(String userId) {
        return Optional.ofNullable(usersById.get(userId));
    }

    @Override
    public boolean existsById(String userId) {
        return usersById.containsKey(userId);
    }
}
