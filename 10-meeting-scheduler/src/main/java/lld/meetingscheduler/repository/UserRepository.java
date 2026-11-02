package lld.meetingscheduler.repository;

import lld.meetingscheduler.model.User;
import lld.meetingscheduler.model.UserId;

import java.util.Optional;

/** Repository abstraction over user storage, so {@code SchedulerService} is decoupled from
 *  whether users live in memory, a SQL table, or an external identity service. */
public interface UserRepository {
    User save(User user);
    Optional<User> findById(UserId id);
    boolean exists(UserId id);
}
