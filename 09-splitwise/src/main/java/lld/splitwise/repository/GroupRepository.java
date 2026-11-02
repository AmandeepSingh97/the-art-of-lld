package lld.splitwise.repository;

import java.util.Optional;

import lld.splitwise.model.Group;

public interface GroupRepository {
    Group save(Group group);
    Optional<Group> findById(String groupId);
    boolean existsById(String groupId);
}
