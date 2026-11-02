package lld.slackchat.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Channel is the addressable container messages are posted into: a public channel, a
 * private channel, or a 1:1 direct message. All three share the same membership and message
 * model; only the join/visibility rules differ (enforced by {@code ChatServiceImpl}, not by
 * this class, to keep {@code Channel} a simple data holder with concurrency-safe membership).
 *
 * <p><b>Thread-safety:</b> {@code members} uses {@link ConcurrentHashMap#newKeySet()}. Channel
 * fan-out (message delivery) iterates this set on every message send -- a read-heavy,
 * write-light access pattern, which is exactly what a concurrent hash-set is optimized for
 * (lock-free reads, fine-grained segment locking on writes). See README section 11.
 */
public final class Channel {

    private final ChannelId id;
    private final WorkspaceId workspaceId;
    private volatile String name;
    private final ChannelType type;
    private final UserId createdBy;
    private final Instant createdAt;
    private final Set<UserId> members = ConcurrentHashMap.newKeySet();

    public Channel(
            ChannelId id,
            WorkspaceId workspaceId,
            String name,
            ChannelType type,
            UserId createdBy,
            Instant createdAt,
            Set<UserId> initialMembers) {
        this.id = Objects.requireNonNull(id, "id");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.members.addAll(Objects.requireNonNull(initialMembers, "initialMembers"));
    }

    public ChannelId id() {
        return id;
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    public String name() {
        return name;
    }

    public void renameTo(String newName) {
        if (type == ChannelType.DIRECT_MESSAGE) {
            throw new IllegalStateException("Direct message channels cannot be renamed");
        }
        this.name = Objects.requireNonNull(newName, "newName");
    }

    public ChannelType type() {
        return type;
    }

    public UserId createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void addMember(UserId userId) {
        members.add(Objects.requireNonNull(userId, "userId"));
    }

    public void removeMember(UserId userId) {
        if (type == ChannelType.DIRECT_MESSAGE) {
            throw new IllegalStateException("Cannot leave a direct message channel");
        }
        members.remove(userId);
    }

    public boolean isMember(UserId userId) {
        return members.contains(userId);
    }

    public int memberCount() {
        return members.size();
    }

    /** Defensive snapshot; iterate this rather than the live set to avoid surprises. */
    public Set<UserId> memberSnapshot() {
        return Set.copyOf(members);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Channel channel)) {
            return false;
        }
        return id.equals(channel.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Channel{id=" + id + ", name='" + name + "', type=" + type + "}";
    }
}
