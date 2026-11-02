package lld.splitwise.service;

import java.util.Set;
import java.util.UUID;

import lld.splitwise.exception.GroupNotFoundException;
import lld.splitwise.model.Group;
import lld.splitwise.repository.GroupRepository;

/** Orchestrates group creation and membership management. */
public final class GroupService {

    private final GroupRepository groupRepository;
    private final UserService userService;

    public GroupService(GroupRepository groupRepository, UserService userService) {
        this.groupRepository = groupRepository;
        this.userService = userService;
    }

    public Group createGroup(String name, String createdByUserId, Set<String> initialMemberIds) {
        userService.requireExists(createdByUserId);
        initialMemberIds.forEach(userService::requireExists);
        Group group = new Group(UUID.randomUUID().toString(), name, createdByUserId, initialMemberIds);
        return groupRepository.save(group);
    }

    public Group getOrThrow(String groupId) {
        return groupRepository.findById(groupId).orElseThrow(() -> new GroupNotFoundException(groupId));
    }

    public void addMember(String groupId, String userId) {
        userService.requireExists(userId);
        Group group = getOrThrow(groupId);
        group.addMember(userId);
    }
}
