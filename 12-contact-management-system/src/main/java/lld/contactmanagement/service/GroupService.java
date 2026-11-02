package lld.contactmanagement.service;

import lld.contactmanagement.exception.GroupNotFoundException;
import lld.contactmanagement.index.IndexManager;
import lld.contactmanagement.model.Group;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Manages {@link Group} lifecycle, including cascading membership cleanup
 * on delete. Depends on {@link ContactService} (to remove the group id from
 * each affected contact through the normal CAS-protected update path, rather
 * than reaching into storage directly) and {@link IndexManager} (to find
 * affected contacts in O(members) via the reverse group index instead of an
 * O(n) scan of every contact).
 */
public final class GroupService {

    private final GroupRepository groupRepository;
    private final ContactService contactService;
    private final IndexManager indexManager;

    public GroupService(
            GroupRepository groupRepository, ContactService contactService, IndexManager indexManager) {
        this.groupRepository = groupRepository;
        this.contactService = contactService;
        this.indexManager = indexManager;
    }

    public Group createGroup(String name) {
        Group group = Group.create(name);
        groupRepository.save(group);
        return group;
    }

    public Group renameGroup(UUID groupId, String newName) {
        Group existing = groupRepository.findById(groupId)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
        Group renamed = existing.withName(newName);
        groupRepository.save(renamed);
        return renamed;
    }

    /** Finds a group by (case-insensitive) name, creating it if absent - used by vCard import. */
    public Group findOrCreateByName(String name) {
        return groupRepository.findByName(name).orElseGet(() -> createGroup(name));
    }

    public Optional<Group> get(UUID groupId) {
        return groupRepository.findById(groupId);
    }

    public Collection<Group> getAll() {
        return groupRepository.findAll();
    }

    /**
     * Deletes a group and removes its membership from every contact that
     * currently belongs to it. Each membership removal goes through
     * {@link ContactService#removeFromGroup}, so it is protected by the same
     * optimistic-concurrency guarantees as any other edit - a contact being
     * concurrently edited elsewhere simply causes that one membership
     * removal to retry, not the whole cascade to fail.
     */
    public void deleteGroup(UUID groupId) {
        if (groupRepository.findById(groupId).isEmpty()) {
            throw new GroupNotFoundException(groupId);
        }
        Set<UUID> memberIds = indexManager.groupMemberIds(groupId);
        for (UUID contactId : memberIds) {
            contactService.removeFromGroup(contactId, groupId);
        }
        groupRepository.delete(groupId);
    }
}
