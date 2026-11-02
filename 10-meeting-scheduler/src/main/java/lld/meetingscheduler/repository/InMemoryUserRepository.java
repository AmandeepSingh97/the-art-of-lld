package lld.meetingscheduler.repository;

import lld.meetingscheduler.model.User;
import lld.meetingscheduler.model.UserId;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory implementation backed by {@link ConcurrentHashMap}. Sufficient for a
 *  single-process LLD solution; see README §12 for why a real database is not required here
 *  and §17 for how this would be swapped for a persistent implementation in production. */
public final class InMemoryUserRepository implements UserRepository {

    private final Map<UserId, User> store = new ConcurrentHashMap<>();

    @Override
    public User save(User user) {
        store.put(user.id(), user);
        return user;
    }

    @Override
    public Optional<User> findById(UserId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public boolean exists(UserId id) {
        return store.containsKey(id);
    }
}
