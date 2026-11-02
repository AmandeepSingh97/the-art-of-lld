package lld.slackchat.presence;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lld.slackchat.events.ChatEvent;
import lld.slackchat.events.EventBus;
import lld.slackchat.model.PresenceStatus;
import lld.slackchat.model.UserId;

/**
 * Tracks each user's current presence status and broadcasts changes on the {@code "presence"}
 * topic of the shared {@link EventBus}.
 *
 * <p><b>Thread-safety:</b> backed by a single {@link ConcurrentHashMap}. Presence updates are
 * frequent (every heartbeat/focus-change from every connected client) and read by many
 * consumers (channel member lists rendering a green dot), so lock-free reads matter. A lost
 * update is not a correctness concern here: presence is inherently last-writer-wins,
 * best-effort state -- there is no requirement to serialize two presence updates from the same
 * user relative to each other beyond "the map holds whichever was written last by wall-clock
 * order of the {@code put} calls", which {@code ConcurrentHashMap} already guarantees per key.
 */
public final class PresenceService {

    private final Map<UserId, PresenceStatus> statusByUser = new ConcurrentHashMap<>();
    private final EventBus eventBus;

    public PresenceService(EventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    public void setStatus(UserId userId, PresenceStatus status) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        statusByUser.put(userId, status);
        eventBus.publish("presence", new ChatEvent.PresenceChangedEvent(userId, status, Instant.now()));
    }

    public PresenceStatus statusOf(UserId userId) {
        return statusByUser.getOrDefault(userId, PresenceStatus.OFFLINE);
    }

    public boolean isOnline(UserId userId) {
        return statusOf(userId) == PresenceStatus.ONLINE;
    }
}
