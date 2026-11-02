package lld.elevatorsystem;

/**
 * Lifecycle state of a physical elevator door. Modeled explicitly (rather than
 * a boolean "isOpen") because the obstruction-safety protocol needs the
 * transient {@code CLOSING} / {@code OBSTRUCTED} states to decide whether a
 * close attempt may proceed.
 */
public enum DoorState {
    CLOSED,
    OPENING,
    OPEN,
    CLOSING,
    /** A close attempt was vetoed by the obstruction sensor; door re-opened. */
    OBSTRUCTED
}
