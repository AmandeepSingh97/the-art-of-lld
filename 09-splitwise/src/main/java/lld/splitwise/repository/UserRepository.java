package lld.splitwise.repository;

import java.util.Optional;

import lld.splitwise.model.User;

/** Repository abstraction over user storage (Repository pattern: isolates persistence from domain/service logic). */
public interface UserRepository {
    User save(User user);
    Optional<User> findById(String userId);
    boolean existsById(String userId);
}
