package lld.slackchat;

import lld.slackchat.events.ChatEvent;
import lld.slackchat.model.Channel;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.Message;
import lld.slackchat.model.PresenceStatus;
import lld.slackchat.model.User;
import lld.slackchat.model.UserId;
import lld.slackchat.model.Workspace;
import lld.slackchat.pagination.MessagePage;
import lld.slackchat.pagination.PageRequest;
import lld.slackchat.service.ChatServiceImpl;

/**
 * Runnable end-to-end walkthrough of the LLD: creates a workspace, a public channel and a DM,
 * subscribes an "observer" to simulate a connected client, sends messages (including a
 * mention), edits one, deletes another, reads paginated history, tracks unread counts, and
 * demonstrates the Token Bucket rate limiter rejecting a noisy sender.
 *
 * <p>This is a demo entry point, not a test: assertions here are {@code System.out} narration
 * for a human reader. See {@code tests/} for the real automated test suite.
 */
public final class Demo {

    public static void main(String[] args) {
        ChatServiceImpl chat = ChatServiceImpl.createDefault();

        User alice = chat.createUser("alice", "Alice Anders");
        User bob = chat.createUser("bob", "Bob Baker");
        User carol = chat.createUser("carol", "Carol Chen");

        Workspace workspace = chat.createWorkspace("Acme Corp", alice.id());
        chat.addUserToWorkspace(workspace.id(), bob.id());
        chat.addUserToWorkspace(workspace.id(), carol.id());

        Channel engineering = chat.createPublicChannel(workspace.id(), "#engineering", alice.id());
        chat.joinChannel(engineering.id(), bob.id());

        // Simulate Bob's connected client subscribing to real-time delivery of messages
        // addressed directly to him (fan-out-on-write mailbox topic).
        chat.eventBus().subscribe("user:" + bob.id(), event -> {
            if (event instanceof ChatEvent.MessageSentEvent sent) {
                System.out.println("[Bob's client] received push: new message " + sent.messageId()
                        + " in channel " + sent.channelId());
            }
        });

        chat.setPresence(bob.id(), PresenceStatus.ONLINE);

        Message m1 = chat.sendMessage(engineering.id(), alice.id(), "Morning team, deploy window is 3pm today");
        Message m2 = chat.sendMessage(engineering.id(), alice.id(), "@bob can you review the rollback plan?");

        System.out.println("Bob's unread count before reading: " + chat.getUnreadCount(engineering.id(), bob.id()));
        System.out.println("Bob's notifications: " + chat.notificationService().notificationsFor(bob.id()).size());

        chat.markRead(engineering.id(), bob.id(), m2.sequence());
        System.out.println("Bob's unread count after reading: " + chat.getUnreadCount(engineering.id(), bob.id()));

        chat.editMessage(engineering.id(), m1.id(), alice.id(), "Morning team, deploy window moved to 4pm today");
        chat.deleteMessage(engineering.id(), m2.id(), alice.id());

        MessagePage page = chat.getHistory(engineering.id(), bob.id(), PageRequest.latest(10));
        System.out.println("History page (" + page.messages().size() + " messages):");
        for (Message m : page.messages()) {
            System.out.println("  seq=" + m.sequence() + " state=" + m.state() + " content='" + m.content() + "'");
        }

        // Direct message between Alice and Carol, created lazily on first contact.
        Channel dm = chat.getOrCreateDirectMessage(workspace.id(), alice.id(), carol.id());
        chat.sendMessage(dm.id(), alice.id(), "Hey Carol, got a minute?");

        demonstrateRateLimiting(chat, engineering.id(), alice.id());
    }

    private static void demonstrateRateLimiting(ChatServiceImpl chat, ChannelId channelId, UserId senderId) {
        System.out.println("\nDemonstrating token-bucket rate limiting with a burst of 8 rapid sends:");
        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 8; i++) {
            try {
                chat.sendMessage(channelId, senderId, "burst message #" + i);
                accepted++;
            } catch (lld.slackchat.exception.RateLimitExceededException e) {
                rejected++;
            }
        }
        System.out.println("Accepted: " + accepted + ", rejected: " + rejected
                + " (bucket capacity defaults to 5 tokens, refilling at 1/sec)");
    }
}
