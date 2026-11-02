package lld.contactmanagement.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhoneNumberEmailAddressTest {

    @Test
    void normalizedStripsPunctuationAndSpaces() {
        PhoneNumber phone = PhoneNumber.of("+1 (555) 123-4567", PhoneType.MOBILE);
        assertEquals("+15551234567", phone.normalized());
    }

    @Test
    void rawValueIsPreservedForDisplay() {
        PhoneNumber phone = PhoneNumber.of("(555) 123-4567", PhoneType.MOBILE);
        assertEquals("(555) 123-4567", phone.rawValue());
    }

    @Test
    void blankPhoneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PhoneNumber.of("   ", PhoneType.HOME));
    }

    @Test
    void phoneWithNoDigitsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PhoneNumber.of("+---", PhoneType.HOME));
    }

    @Test
    void emailIsLowercasedAndTrimmedEagerly() {
        EmailAddress email = EmailAddress.of("  John.Doe@Example.COM  ", EmailType.WORK);
        assertEquals("john.doe@example.com", email.value());
    }

    @Test
    void invalidEmailIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> EmailAddress.of("not-an-email", EmailType.OTHER));
    }

    @Test
    void emailEqualityIsCaseInsensitiveBecauseOfEagerNormalization() {
        EmailAddress a = EmailAddress.of("Jane@Example.com", EmailType.PERSONAL);
        EmailAddress b = EmailAddress.of("jane@example.com", EmailType.PERSONAL);
        assertEquals(a, b);
    }
}
