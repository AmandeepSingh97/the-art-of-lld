package lld.bookmyshow.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Value object returned to a caller after successfully holding a set of
 * seats for a show. Immutable — represents a point-in-time grant, not a
 * live mutable handle. The source of truth for whether the lock is still
 * valid is always the corresponding {@link ShowSeat} state, not this object.
 */
public final class SeatLock {
    private final String lockId;
    private final String showId;
    private final List<String> seatIds;
    private final String ownerId;
    private final Instant expiresAt;

    public SeatLock(String lockId, String showId, List<String> seatIds, String ownerId, Instant expiresAt) {
        this.lockId = Objects.requireNonNull(lockId, "lockId");
        this.showId = Objects.requireNonNull(showId, "showId");
        this.seatIds = List.copyOf(seatIds);
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public String getLockId() {
        return lockId;
    }

    public String getShowId() {
        return showId;
    }

    public List<String> getSeatIds() {
        return seatIds;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
