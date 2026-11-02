package lld.splitwise.service;

import lld.splitwise.exception.UserNotFoundException;
import lld.splitwise.model.User;
import lld.splitwise.repository.UserRepository;

import java.util.UUID;

/** Orchestrates user registration/lookup. Thin by design: no business rules live here beyond existence checks. */
public final class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User register(String name, String email) {
        String id = UUID.randomUUID().toString();
        User user = new User(id, name, email);
        return userRepository.save(user);
    }

    public User getOrThrow(String userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    public void requireExists(String userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
    }
}
