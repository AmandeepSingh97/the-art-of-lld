package lld.slackchat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lld.slackchat.exception.ChannelNotFoundException;
import lld.slackchat.exception.InvalidChannelOperationException;
import lld.slackchat.exception.NotAChannelMemberException;
import lld.slackchat.exception.RateLimitExceededException;
import lld.slackchat.model.Channel;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.Message;
import lld.slackchat.model.User;
import lld.slackchat.model.UserId;
import lld.slackchat.model.Workspace;
import lld.slackchat.pagination.MessagePage;
import lld.slackchat.pagination.PageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatServiceImplTest {

    private ChatServiceImpl chat;
    private User alice;
    private User bob;
    private User carol;
    private Workspace workspace;
    private Channel channel;

    @BeforeEach
    void setUp() {
        chat = ChatServiceImpl.createDefault();
        alice = chat.createUser("alice", "Alice");
        bob = chat.createUser("bob", "Bob");
        carol = chat.createUser("carol", "Carol");
        workspace = chat.createWorkspace("Acme", alice.id());
        chat.addUserToWorkspace(workspace.id(), bob.id());
        chat.addUserToWorkspace(workspace.id(), carol.id());
        channel = chat.createPublicChannel(workspace.id(), "#general", alice.id());
        chat.joinChannel(channel.id(), bob.id());
    }

    @Test
    void sendMessageByNonMemberIsRejected() {
        assertThrows(NotAChannelMemberException.class,
                () -> chat.sendMessage(channel.id(), carol.id(), "I shouldn't be able to post here"));
    }

    @Test
    void sendMessageToUnknownChannelIsRejected() {
        assertThrows(ChannelNotFoundException.class,
                () -> chat.sendMessage(ChannelId.generate(), alice.id(), "hello?"));
    }

    @Test
    void privateChannelCannotBeJoinedDirectly() {
        Channel privateChannel = chat.createPrivateChannel(workspace.id(), "#leadership", alice.id(), Set.of());
        assertThrows(InvalidChannelOperationException.class,
                () -> chat.joinChannel(privateChannel.id(), bob.id()));
    }

    @Test
    void mentionGeneratesANotificationOnlyForChannelMembers() {
        chat.sendMessage(channel.id(), alice.id(), "@bob please check this, also cc @carol");

        assertEquals(1, chat.notificationService().notificationsFor(bob.id()).size());
        assertEquals(0, chat.notificationService().notificationsFor(carol.id()).size(),
                "carol is mentioned but is not a channel member, so she must not be notified");
    }

    @Test
    void unreadCountTracksMarkReadAccurately() {
        chat.sendMessage(channel.id(), alice.id(), "msg 1");
        Message second = chat.sendMessage(channel.id(), alice.id(), "msg 2");
        chat.sendMessage(channel.id(), alice.id(), "msg 3");

        assertEquals(3L, chat.getUnreadCount(channel.id(), bob.id()));

        chat.markRead(channel.id(), bob.id(), second.sequence());

        assertEquals(1L, chat.getUnreadCount(channel.id(), bob.id()));
    }

    @Test
    void editedMessageIsReflectedInHistoryWithAuditTrail() {
        Message message = chat.sendMessage(channel.id(), alice.id(), "origin content");
        chat.editMessage(channel.id(), message.id(), alice.id(), "corrected content");

        MessagePage page = chat.getHistory(channel.id(), alice.id(), PageRequest.latest(10));
        Message edited = page.messages().get(0);
        assertEquals("corrected content", edited.content());
        assertEquals(1, edited.editHistory().size());
    }

    @Test
    void deletedMessageIsTombstonedButStillCountedInHistory() {
        Message message = chat.sendMessage(channel.id(), alice.id(), "oops");
        chat.deleteMessage(channel.id(), message.id(), alice.id());

        MessagePage page = chat.getHistory(channel.id(), alice.id(), PageRequest.latest(10));
        assertEquals(1, page.messages().size());
        assertEquals("[message deleted]", page.messages().get(0).content());
    }

    @Test
    void rateLimiterRejectsBurstBeyondCapacityFromTheSameSenderOnly() {
        // default TokenBucketRateLimiter(5, 1.0) from createDefault()
        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 8; i++) {
            try {
                chat.sendMessage(channel.id(), alice.id(), "spam " + i);
                accepted++;
            } catch (RateLimitExceededException e) {
                rejected++;
            }
        }
        assertEquals(5, accepted, "exactly the bucket capacity should be accepted in an instantaneous burst");
        assertEquals(3, rejected);

        // Bob, a different sender, must be unaffected by Alice's burst.
        assertTrue(bobCanStillSend());
    }

    private boolean bobCanStillSend() {
        chat.sendMessage(channel.id(), bob.id(), "I'm a different user, I should be fine");
        return true;
    }

    @Test
    void getOrCreateDirectMessageIsIdempotentAndSymmetric() {
        Channel dm1 = chat.getOrCreateDirectMessage(workspace.id(), alice.id(), bob.id());
        Channel dm2 = chat.getOrCreateDirectMessage(workspace.id(), bob.id(), alice.id());

        assertEquals(dm1.id(), dm2.id(), "requesting a DM in either member order must return the same channel");
    }

    @Test
    void concurrentSendersInTheSameChannelProduceConsistentHistoryAndUnreadCounts() throws InterruptedException {
        // Add many more members so we exercise the fan-out-on-write path with real contention.
        int senderCount = 10;
        int messagesPerSender = 20;
        List<User> senders = new java.util.ArrayList<>();
        for (int i = 0; i < senderCount; i++) {
            User u = chat.createUser("user" + i, "User " + i);
            chat.addUserToWorkspace(workspace.id(), u.id());
            chat.joinChannel(channel.id(), u.id());
            senders.add(u);
        }

        ExecutorService pool = Executors.newFixedThreadPool(senderCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(senderCount);

        for (User sender : senders) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < messagesPerSender; i++) {
                        try {
                            chat.sendMessage(channel.id(), sender.id(), "msg from " + sender.username());
                        } catch (RateLimitExceededException ignored) {
                            // each independent sender has its own bucket; some sends may be
                            // throttled, which is expected and does not indicate a bug.
                        }
                    }
                } catch (InterruptedException ignored) {
                    // handled by latch accounting below
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        // Whatever subset got through, the store's sequence numbers must be unique, dense
        // from 1..N and the full history must be retrievable without gaps. Pagination is
        // capped at 500/page by design (see PageRequest), so page through forward cursors.
        List<Message> all = new java.util.ArrayList<>();
        long cursor = 0L;
        while (true) {
            MessagePage page = chat.getHistory(channel.id(), alice.id(), PageRequest.after(cursor, 500));
            all.addAll(page.messages());
            if (!page.hasMore() || page.messages().isEmpty()) {
                break;
            }
            cursor = page.nextCursor().getAsLong();
        }
        long latest = all.stream().mapToLong(Message::sequence).max().orElse(0L);
        assertEquals(latest, all.size(),
                "every sequence number from 1..latest must be present exactly once");
    }
}
