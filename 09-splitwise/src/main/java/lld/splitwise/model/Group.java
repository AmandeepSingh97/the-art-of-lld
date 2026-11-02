package lld.splitwise.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A group of users who share expenses together (e.g. "Goa Trip", "Flatmates").
 *
 * <p><b>Mutability and thread-safety:</b> unlike {@link User} or {@link Expense},
 * a {@link Group}'s membership can change after creation (members join/leave),
 * so this class cannot be fully immutable. Membership is backed by a
 * {@link CopyOnWriteArraySet}: group membership changes are rare relative to
 * reads (every expense add/balance query reads the member set, but members are
 * added/removed infrequently), so copy-on-write gives lock-free, consistent
 * snapshot iteration without blocking readers — a deliberate read-optimized
 * trade-off. Group {@code id}/{@code name}/{@code createdBy} are immutable.
 */
public final class Group {

    private final String id;
    private final String name;
    private final String createdByUserId;
    private final Set<String> memberUserIds = new CopyOnWriteArraySet<>();

    public Group(String id, String name, String createdByUserId, Set<String> initialMembers) {
        this.id = id;
        this.name = name;
        this.createdByUserId = createdByUserId;
        this.memberUserIds.addAll(initialMembers);
        this.memberUserIds.add(createdByUserId);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String createdByUserId() {
        return createdByUserId;
    }

    /** Returns an unmodifiable snapshot of current member ids. */
    public Set<String> memberIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(memberUserIds));
    }

    public boolean isMember(String userId) {
        return memberUserIds.contains(userId);
    }

    public void addMember(String userId) {
        memberUserIds.add(userId);
    }

    public void removeMember(String userId) {
        memberUserIds.remove(userId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Group group)) return false;
        return id.equals(group.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Group{id='%s', name='%s', members=%d}".formatted(id, name, memberUserIds.size());
    }
}
