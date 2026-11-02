package lld.elevatorsystem.door;

import lld.elevatorsystem.DoorState;

/**
 * Models a single elevator cabin door, including the obstruction-safety
 * protocol. The door is advanced one discrete time step at a time via
 * {@link #tick()}, driven by the owning elevator's {@code DoorOpenState}.
 *
 * <p><b>Safety invariant:</b> a door never transitions {@code CLOSING ->
 * CLOSED} while {@link ObstructionSensor#isObstructed()} reports {@code
 * true} at the moment of the close attempt. On an obstruction the door is
 * held/re-opened and the close is retried on a fresh timer. This favors
 * passenger safety over throughput — the correct trade-off for physical
 * doors on a people-mover.
 *
 * <p><b>Thread-safety:</b> a {@code Door} is owned exclusively by its parent
 * {@code Elevator} and is only ever mutated from that elevator's single
 * movement/tick thread (see {@code Elevator} javadoc for the single-writer
 * threading model). The one exception is {@link ObstructionSensor#isObstructed()}
 * itself, which may be backed by a hardware callback on another thread; the
 * sensor implementation is responsible for its own internal thread-safety
 * (e.g. an {@code AtomicBoolean}) since {@code Door} only ever reads it.
 */
public final class Door {

    /** How many ticks the door stays fully open before attempting to close. */
    private static final int HOLD_TICKS = 3;

    /**
     * How many consecutive obstruction detections are tolerated before the
     * jam is surfaced via {@link #isJammed()} for operational alerting. The
     * door keeps retrying regardless (never force-closes), but after this
     * many retries the situation is no longer "normal operation".
     */
    private static final int JAM_ALERT_THRESHOLD = 3;

    private final ObstructionSensor sensor;
    private DoorState state = DoorState.CLOSED;
    private int holdTicksRemaining;
    private int consecutiveObstructions;

    public Door(ObstructionSensor sensor) {
        this.sensor = sensor;
    }

    /** Begins the open sequence; called when the elevator arrives at a stop. */
    public void beginOpening() {
        state = DoorState.OPENING;
        consecutiveObstructions = 0;
    }

    /**
     * Advances the door state machine by one tick. Call once per controller
     * tick while the elevator is in {@code DOOR_OPEN}.
     */
    public void tick() {
        switch (state) {
            case OPENING -> {
                state = DoorState.OPEN;
                holdTicksRemaining = HOLD_TICKS;
            }
            case OPEN -> {
                if (holdTicksRemaining > 0) {
                    holdTicksRemaining--;
                } else {
                    attemptClose();
                }
            }
            case OBSTRUCTED -> {
                // Give passengers/objects a fresh hold window, then retry.
                state = DoorState.OPEN;
                holdTicksRemaining = HOLD_TICKS;
            }
            case CLOSING -> state = DoorState.CLOSED;
            case CLOSED -> { /* nothing to do; elevator will move next */ }
        }
    }

    private void attemptClose() {
        if (sensor.isObstructed()) {
            consecutiveObstructions++;
            state = DoorState.OBSTRUCTED;
        } else {
            consecutiveObstructions = 0;
            state = DoorState.CLOSING;
        }
    }

    /** @return {@code true} once the door has fully closed and travel may resume. */
    public boolean isClosed() {
        return state == DoorState.CLOSED;
    }

    public DoorState state() {
        return state;
    }

    /**
     * @return {@code true} if the door has been repeatedly blocked from
     *         closing; a production system would raise a monitoring alert
     *         (e.g. "door 3 of car 2 possibly jammed") at this point.
     */
    public boolean isJammed() {
        return consecutiveObstructions >= JAM_ALERT_THRESHOLD;
    }
}
