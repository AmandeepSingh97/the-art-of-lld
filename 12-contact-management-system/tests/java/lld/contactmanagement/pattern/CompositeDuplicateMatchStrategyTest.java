package lld.contactmanagement.pattern;

import lld.contactmanagement.model.Contact;
import lld.contactmanagement.model.EmailAddress;
import lld.contactmanagement.model.EmailType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeDuplicateMatchStrategyTest {

    private Contact contact(String name, String email) {
        return Contact.builder().displayName(name).addEmail(EmailAddress.of(email, EmailType.OTHER)).build();
    }

    @Test
    void anyModeMatchesIfAtLeastOneStrategyAgrees() {
        CompositeDuplicateMatchStrategy strategy = new CompositeDuplicateMatchStrategy(
                List.of(new NormalizedNameMatchStrategy(), new ExactContactPointMatchStrategy()),
                CompositeDuplicateMatchStrategy.Mode.ANY);

        Contact a = contact("Will Byers", "will1@example.com");
        Contact b = contact("will   byers", "will2@example.com"); // only name matches

        assertTrue(strategy.isDuplicate(a, b));
    }

    @Test
    void allModeRequiresEveryStrategyToAgree() {
        CompositeDuplicateMatchStrategy strategy = new CompositeDuplicateMatchStrategy(
                List.of(new NormalizedNameMatchStrategy(), new ExactContactPointMatchStrategy()),
                CompositeDuplicateMatchStrategy.Mode.ALL);

        Contact a = contact("Will Byers", "will1@example.com");
        Contact b = contact("will   byers", "will2@example.com"); // only name matches, not email

        assertFalse(strategy.isDuplicate(a, b));
    }

    @Test
    void allModeMatchesWhenEveryStrategyAgrees() {
        CompositeDuplicateMatchStrategy strategy = new CompositeDuplicateMatchStrategy(
                List.of(new NormalizedNameMatchStrategy(), new ExactContactPointMatchStrategy()),
                CompositeDuplicateMatchStrategy.Mode.ALL);

        Contact a = contact("Will Byers", "will@example.com");
        Contact b = contact("Will Byers", "will@example.com");

        assertTrue(strategy.isDuplicate(a, b));
    }
}
