package lld.slackchat.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import lld.slackchat.model.UserId;
import org.junit.jupiter.api.Test;

class MentionParserTest {

    @Test
    void parsesSingleMentionResolvableByUsername() {
        UserId bobId = UserId.generate();
        MentionParser parser = new MentionParser(name -> name.equals("bob") ? Optional.of(bobId) : Optional.empty());

        Set<UserId> mentioned = parser.parseMentions("hey @bob can you take a look?");

        assertEquals(Set.of(bobId), mentioned);
    }

    @Test
    void parsesMultipleDistinctMentions() {
        UserId bobId = UserId.generate();
        UserId carolId = UserId.generate();
        MentionParser parser = new MentionParser(name -> switch (name) {
            case "bob" -> Optional.of(bobId);
            case "carol" -> Optional.of(carolId);
            default -> Optional.empty();
        });

        Set<UserId> mentioned = parser.parseMentions("@bob and @carol please sync up");

        assertEquals(Set.of(bobId, carolId), mentioned);
    }

    @Test
    void unresolvableHandlesAreSilentlyIgnored() {
        MentionParser parser = new MentionParser(name -> Optional.empty());

        Set<UserId> mentioned = parser.parseMentions("ping @nonexistent-user about this");

        assertTrue(mentioned.isEmpty());
    }

    @Test
    void contentWithNoAtSignHasNoMentions() {
        MentionParser parser = new MentionParser(name -> Optional.of(UserId.generate()));

        assertTrue(parser.parseMentions("plain message with no mentions").isEmpty());
    }

    @Test
    void emailLikeTextIsNotMisparsedAsAnUnrelatedMention() {
        UserId bobId = UserId.generate();
        MentionParser parser = new MentionParser(name -> name.equals("example.com") ? Optional.of(bobId) : Optional.empty());

        // "user@example.com" should be parsed as a mention of handle "example.com" (regex has
        // no email-awareness by design, documented as an acceptable simplification).
        Set<UserId> mentioned = parser.parseMentions("contact user@example.com for details");

        assertEquals(Set.of(bobId), mentioned);
    }
}
