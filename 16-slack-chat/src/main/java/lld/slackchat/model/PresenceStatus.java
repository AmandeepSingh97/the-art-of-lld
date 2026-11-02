package lld.slackchat.model;

/**
 * Presence status of a {@link User}, as tracked by the presence subsystem.
 *
 * <p>{@code AWAY} is distinct from {@code OFFLINE}: a user can be connected but idle
 * (client-reported inactivity) versus fully disconnected (no active session). Production
 * systems typically derive {@code AWAY} from client heartbeats/idle timers and {@code OFFLINE}
 * from the last connection being torn down (or a heartbeat timeout).
 */
public enum PresenceStatus {
    ONLINE,
    AWAY,
    OFFLINE
}
