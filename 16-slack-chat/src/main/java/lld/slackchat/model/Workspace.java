package lld.slackchat.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Workspace (a.k.a. "team" / "org") groups users and channels, mirroring a single Slack
 * workspace. Membership is the authority for "can this user join a public channel / be
 * @mentioned / be found in people search" -- a user must be a workspace member before they
 * can be added to any channel inside it.
 *
 * <p><b>Thread-safety:</b> {@code members} is a {@link ConcurrentHashMap#newKeySet()} backed
 * set. Membership changes (join/leave workspace) are rare relative to reads (membership
 * checks happen on every message send), so a concurrent set that favours cheap, lock-free
 * reads is the right structure -- see README section 11 for the full justification.
 */
public final class Workspace {

    private final WorkspaceId id;
    private final String name;
    private final UserId ownerId;
    private final Instant createdAt;
    private final Set<UserId> members = ConcurrentHashMap.newKeySet();

    public Workspace(WorkspaceId id, String name, UserId ownerId, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.members.add(ownerId);
    }

    public WorkspaceId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public UserId ownerId() {
        return ownerId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void addMember(UserId userId) {
        members.add(Objects.requireNonNull(userId, "userId"));
    }

    public void removeMember(UserId userId) {
        if (userId.equals(ownerId)) {
            throw new IllegalStateException("Workspace owner cannot be removed from the workspace");
        }
        members.remove(userId);
    }

    public boolean isMember(UserId userId) {
        return members.contains(userId);
    }

    /** Defensive snapshot; callers must not assume this reflects subsequent mutations. */
    public Set<UserId> memberSnapshot() {
        return Set.copyOf(members);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Workspace workspace)) {
            return false;
        }
        return id.equals(workspace.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
