package lld.elevatorsystem.door;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the door obstruction-safety protocol in isolation (no
 * elevator/state machine involved), including the "faithful" negative
 * control required to prove the obstruction guard actually does something:
 * a sensor that is obstructed exactly at the moment of the close attempt
 * must prevent {@code CLOSED} from being reached that cycle.
 */
class DoorTest {

    @Test
    void doorOpensHoldsThenClosesWhenNeverObstructed() {
        Door door = new Door(ObstructionSensor.alwaysClear());
        door.beginOpening();

        door.tick(); // OPENING -> OPEN
        assertEquals(lld.elevatorsystem.DoorState.OPEN, door.state());

        door.tick(); // OPEN, holding (3 -> 2)
        door.tick(); // holding (2 -> 1)
        door.tick(); // holding (1 -> 0)
        assertEquals(lld.elevatorsystem.DoorState.OPEN, door.state());

        door.tick(); // hold exhausted -> attempt close -> CLOSING
        assertEquals(lld.elevatorsystem.DoorState.CLOSING, door.state());
        assertFalse(door.isClosed());

        door.tick(); // CLOSING -> CLOSED
        assertTrue(door.isClosed());
    }

    @Test
    void obstructionDuringCloseAttemptReopensInsteadOfClosing() {
        // Faithful negative control: obstructed on the very tick the door would otherwise close.
        AtomicInteger checks = new AtomicInteger(0);
        // Faithful negative control: obstructed on the very tick the door would otherwise close.
        ObstructionSensor obstructedOnce = () -> checks.incrementAndGet() == 1;
        Door door = new Door(obstructedOnce);
        door.beginOpening();
        door.tick(); // OPENING -> OPEN
        door.tick(); // holding (3 -> 2)
        door.tick(); // holding (2 -> 1)
        door.tick(); // holding (1 -> 0)
        door.tick(); // hold exhausted -> attemptClose() -> sensor reports obstructed -> OBSTRUCTED

        assertEquals(lld.elevatorsystem.DoorState.OBSTRUCTED, door.state());
        assertFalse(door.isClosed(), "a door must never report closed while the obstruction sensor fired on the close attempt");
    }

    @Test
    void doorEventuallyClosesOnceObstructionClears() {
        AtomicInteger attempts = new AtomicInteger(0);
        // Obstructed for the first two close attempts, then clear.
        ObstructionSensor flaky = () -> attempts.incrementAndGet() <= 2;
        Door door = new Door(flaky);
        door.beginOpening();

        boolean closed = false;
        for (int i = 0; i < 30 && !closed; i++) {
            door.tick();
            closed = door.isClosed();
        }
        assertTrue(closed, "door must eventually close once the obstruction clears");
    }

    @Test
    void repeatedObstructionsAreSurfacedAsJammedForOperationalAlerting() {
        ObstructionSensor alwaysObstructed = () -> true;
        Door door = new Door(alwaysObstructed);
        door.beginOpening();

        for (int i = 0; i < 20; i++) {
            door.tick();
        }
        assertTrue(door.isJammed());
        assertFalse(door.isClosed(), "a jammed door must never force-close over an obstruction");
    }
}
