package lld.slackchat.service;

import java.util.Set;
import lld.slackchat.model.Channel;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.Message;
import lld.slackchat.model.MessageId;
import lld.slackchat.model.PresenceStatus;
import lld.slackchat.model.User;
import lld.slackchat.model.UserId;
import lld.slackchat.model.Workspace;
import lld.slackchat.model.WorkspaceId;
import lld.slackchat.pagination.MessagePage;
import lld.slackchat.pagination.PageRequest;

/**
 * Facade over the whole chat subsystem: the single entry point a client/controller layer
 * would call. It exists so callers do not need to know about (or correctly sequence calls
 * across) the message store, unread tracker, event bus, rate limiter, presence service and
 * notification service individually -- see README section 7 for why Facade was chosen here
 * over exposing each subsystem directly.
 */
public interface ChatService {

    User createUser(String username, String displayName);

    Workspace createWorkspace(String name, UserId ownerId);

    void addUserToWorkspace(WorkspaceId workspaceId, UserId userId);

    Channel createPublicChannel(WorkspaceId workspaceId, String name, UserId creatorId);

    Channel createPrivateChannel(
            WorkspaceId workspaceId, String name, UserId creatorId, Set<UserId> initialMembers);

    /** Returns the existing 1:1 DM channel between the two users, creating it on first contact. */
    Channel getOrCreateDirectMessage(WorkspaceId workspaceId, UserId userA, UserId userB);

    void joinChannel(ChannelId channelId, UserId userId);

    void leaveChannel(ChannelId channelId, UserId userId);

    Message sendMessage(ChannelId channelId, UserId senderId, String content);

    Message editMessage(ChannelId channelId, MessageId messageId, UserId editorId, String newContent);

    void deleteMessage(ChannelId channelId, MessageId messageId, UserId requesterId);

    MessagePage getHistory(ChannelId channelId, UserId requesterId, PageRequest pageRequest);

    void markRead(ChannelId channelId, UserId userId, long uptoSequence);

    long getUnreadCount(ChannelId channelId, UserId userId);

    void setPresence(UserId userId, PresenceStatus status);
}
