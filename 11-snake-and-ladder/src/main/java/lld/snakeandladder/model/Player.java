package lld.snakeandladder.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a participant in a Snake and Ladder game.
 *
 * <p><b>Identity:</b> two {@code Player} instances are equal if and only if
 * they share the same {@code id}. {@code name} is display metadata only and
 * is deliberately excluded from {@link #equals(Object)}/{@link #hashCode()}
 * so that renaming a player (not supported today, but a plausible future
 * feature) would never silently change set/map membership semantics.</p>
 *
 * <p><b>Mutability:</b> {@code position} is the one piece of genuinely
 * mutable state in the whole model. It changes once per turn and is only
 * ever mutated by the {@code Game} engine via {@link #moveTo(int)}, never
 * read-modify-written by two different call sites. See
 * {@code README.md §11 Concurrency and Thread Safety} for why this class is
 * intentionally <b>not</b> made thread-safe internally and is instead
 * protected by the engine's higher-level synchronization.</p>
 */
public final class Player {

    private final String id;
    private final String name;
    private volatile int position;

    private Player(String id, String name, int startingPosition) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Player name must not be blank");
        }
        this.position = startingPosition;
    }

    /**
     * Creates a new player with a system-generated unique id, starting at
     * position 0 (i.e. "off the board", before square 1).
     */
    public static Player of(String name) {
        return new Player(UUID.randomUUID().toString(), name, 0);
    }

    /**
     * Creates a player with a caller-supplied id. Useful in tests where
     * deterministic ids make assertions and debugging easier.
     */
    public static Player withId(String id, String name) {
        return new Player(id, name, 0);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** Current board position; 0 means "not yet entered the board". */
    public int position() {
        return position;
    }

    /**
     * Updates this player's position. Package-private by convention of use
     * (public for engine/test access across packages) but intended to be
     * called exclusively by the single-threaded {@code Game} turn loop.
     */
    public void moveTo(int newPosition) {
        if (newPosition < 0) {
            throw new IllegalArgumentException("Position cannot be negative: " + newPosition);
        }
        this.position = newPosition;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Player player)) {
            return false;
        }
        return id.equals(player.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Player{id='%s', name='%s', position=%d}".formatted(id, name, position);
    }
}
