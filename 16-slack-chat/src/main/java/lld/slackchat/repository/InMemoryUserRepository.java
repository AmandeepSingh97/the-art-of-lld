package lld.slackchat.repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lld.slackchat.model.User;
import lld.slackchat.model.UserId;

/**
 * In-memory {@link UserRepository}.
 *
 * <p><b>Thread-safety:</b> two {@link ConcurrentHashMap}s (by id, by lowercase username).
 * {@code save} must keep both maps consistent; since both operations are simple puts keyed by
 * values derived from the same immutable {@code User} fields (id, username never change after
 * construction -- see {@link User}), there is no interleaving that can leave the maps
 * inconsistent with each other, so no additional locking is required beyond what each map
 * provides internally.
 */
public final class InMemoryUserRepository implements UserRepository {

    private final Map<UserId, User> byId = new ConcurrentHashMap<>();
    private final Map<String, User> byUsername = new ConcurrentHashMap<>();

    @Override
    public User save(User user) {
        byId.put(user.id(), user);
        byUsername.put(user.username().toLowerCase(), user);
        return user;
    }

    @Override
    public Optional<User> findById(UserId id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username.toLowerCase()));
    }

    @Override
    public boolean existsByUsername(String username) {
        return byUsername.containsKey(username.toLowerCase());
    }
}
