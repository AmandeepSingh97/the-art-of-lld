package lld.elevatorsystem.request;

import lld.elevatorsystem.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HallRequestTest {

    @Test
    void rejectsIdleDirectionAtConstructionTime() {
        assertThrows(IllegalArgumentException.class, () -> new HallRequest(3, Direction.IDLE, 0));
    }

    @Test
    void exposesItsOwnDirectionAsTheRequestedDirection() {
        HallRequest request = new HallRequest(3, Direction.UP, 10);
        assertEquals(Direction.UP, request.requestedDirection());
        assertEquals(3, request.floor());
        assertEquals(10, request.createdAtTick());
    }

    @Test
    void cabinRequestReportsIdleAsItsRequestedDirection() {
        CabinRequest request = new CabinRequest(5, 2);
        assertEquals(Direction.IDLE, request.requestedDirection());
    }
}
