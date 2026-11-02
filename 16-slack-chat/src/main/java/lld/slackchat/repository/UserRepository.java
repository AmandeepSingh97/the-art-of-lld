package lld.slackchat.repository;

import java.util.Optional;
import lld.slackchat.model.User;
import lld.slackchat.model.UserId;

public interface UserRepository {
    User save(User user);

    Optional<User> findById(UserId id);

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}
