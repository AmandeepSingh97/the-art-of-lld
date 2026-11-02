package lld.clouddrive.notification;

import java.time.Instant;

/** Immutable event describing a change to a drive node, published for subscribers (Observer). */
public final class FileChangeEvent {

    public enum Type { CREATED, NEW_VERSION, RENAMED, MOVED, TRASHED, RESTORED, PURGED, SHARED, UNSHARED }

    private final Type type;
    private final String nodeId;
    private final String actorUserId;
    private final Instant occurredAt;

    public FileChangeEvent(Type type, String nodeId, String actorUserId, Instant occurredAt) {
        this.type = type;
        this.nodeId = nodeId;
        this.actorUserId = actorUserId;
        this.occurredAt = occurredAt;
    }

    public Type getType() {
        return type;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public String toString() {
        return "[" + occurredAt + "] " + type + " node=" + nodeId + " by=" + actorUserId;
    }
}
