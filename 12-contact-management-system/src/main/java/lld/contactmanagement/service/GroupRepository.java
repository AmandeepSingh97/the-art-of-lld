package lld.contactmanagement.service;

import lld.contactmanagement.model.Group;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Storage abstraction for {@link Group} entities. */
public interface GroupRepository {

    void save(Group group);

    Optional<Group> findById(UUID id);

    Optional<Group> findByName(String name);

    Collection<Group> findAll();

    void delete(UUID id);
}
