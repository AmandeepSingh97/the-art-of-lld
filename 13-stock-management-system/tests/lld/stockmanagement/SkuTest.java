package lld.stockmanagement;

import lld.stockmanagement.model.Sku;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for the {@link Sku} value object: validation and equality contract. */
class SkuTest {

    @Test
    void equalSkuCodesAreEqualRegardlessOfOtherFields() {
        Sku a = new Sku("ABC-1", "Widget", "EACH");
        Sku b = new Sku("ABC-1", "Different Name", "BOX");
        assertEquals(a, b, "Sku identity is the skuCode, not the other descriptive fields");
        assertEquals(a.hashCode(), b.hashCode(), "equal objects must have equal hashCodes");
    }

    @Test
    void differentSkuCodesAreNotEqual() {
        Sku a = new Sku("ABC-1", "Widget", "EACH");
        Sku b = new Sku("ABC-2", "Widget", "EACH");
        assertNotEquals(a, b);
    }

    @Test
    void blankSkuCodeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Sku("  ", "Widget", "EACH"));
    }

    @Test
    void nullNameIsRejected() {
        assertThrows(NullPointerException.class, () -> new Sku("ABC-1", null, "EACH"));
    }
}
