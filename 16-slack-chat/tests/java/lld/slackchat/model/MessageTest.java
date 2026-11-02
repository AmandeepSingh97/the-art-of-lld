package lld.slackchat.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Set;
import lld.slackchat.exception.InvalidMessageStateException;
import lld.slackchat.exception.UnauthorizedMessageActionException;
import org.junit.jupiter.api.Test;

class MessageTest {

    private final UserId sender = UserId.generate();
    private final UserId stranger = UserId.generate();
    private final ChannelId channelId = ChannelId.generate();

    private Message newMessage(String content) {
        return new Message(MessageId.generate(), channelId, sender, 1L, content, Set.of(), Instant.now());
    }

    @Test
    void editBySenderUpdatesContentAndRecordsAuditEntry() {
        Message message = newMessage("original content");

        message.edit(sender, "updated content", Set.of());

        assertEquals("updated content", message.content());
        assertEquals(MessageState.EDITED, message.state());
        assertEquals(1, message.editHistory().size());
        assertEquals("original content", message.editHistory().get(0).previousContent());
    }

    @Test
    void editByNonSenderIsRejected() {
        Message message = newMessage("original content");

        assertThrows(UnauthorizedMessageActionException.class,
                () -> message.edit(stranger, "hacked content", Set.of()));
        assertEquals("original content", message.content(), "content must be unchanged after a rejected edit");
    }

    @Test
    void deletedMessageCannotBeEditedEvenBySender() {
        Message message = newMessage("original content");
        message.delete(sender);

        assertThrows(InvalidMessageStateException.class, () -> message.edit(sender, "too late", Set.of()));
    }

    @Test
    void deleteIsIdempotentlyRejectedOnSecondAttempt() {
        Message message = newMessage("original content");
        message.delete(sender);

        assertThrows(InvalidMessageStateException.class, () -> message.delete(sender));
    }

    @Test
    void deletePreservesAuditTrailAndTombstonesContent() {
        Message message = newMessage("secret plans");
        message.delete(sender);

        assertEquals("[message deleted]", message.content());
        assertEquals(MessageState.DELETED, message.state());
        assertTrue(message.deletedAt() != null);
        assertEquals("secret plans", message.editHistory().get(0).previousContent());
    }

    @Test
    void deleteByNonSenderIsRejected() {
        Message message = newMessage("original content");

        assertThrows(UnauthorizedMessageActionException.class, () -> message.delete(stranger));
        assertEquals(MessageState.ACTIVE, message.state());
    }

    @Test
    void blankContentIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new Message(MessageId.generate(), channelId, sender, 1L, "   ", Set.of(), Instant.now()));
    }
}
