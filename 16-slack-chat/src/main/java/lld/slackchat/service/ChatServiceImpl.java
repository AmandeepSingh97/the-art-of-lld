package lld.slackchat.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lld.slackchat.delivery.ChannelDeliveryPolicy;
import lld.slackchat.delivery.ChannelDeliveryStrategy;
import lld.slackchat.events.ChatEvent;
import lld.slackchat.events.EventBus;
import lld.slackchat.exception.ChannelNotFoundException;
import lld.slackchat.exception.InvalidChannelOperationException;
import lld.slackchat.exception.MessageNotFoundException;
import lld.slackchat.exception.NotAChannelMemberException;
import lld.slackchat.exception.RateLimitExceededException;
import lld.slackchat.exception.UserNotFoundException;
import lld.slackchat.exception.WorkspaceNotFoundException;
import lld.slackchat.factory.ChannelFactory;
import lld.slackchat.model.Channel;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.ChannelType;
import lld.slackchat.model.Message;
import lld.slackchat.model.MessageId;
import lld.slackchat.model.PresenceStatus;
import lld.slackchat.model.User;
import lld.slackchat.model.UserId;
import lld.slackchat.model.Workspace;
import lld.slackchat.model.WorkspaceId;
import lld.slackchat.notification.MentionParser;
import lld.slackchat.notification.NotificationService;
import lld.slackchat.pagination.MessagePage;
import lld.slackchat.pagination.PageRequest;
import lld.slackchat.presence.PresenceService;
import lld.slackchat.ratelimit.RateLimiter;
import lld.slackchat.repository.ChannelRepository;
import lld.slackchat.repository.InMemoryChannelRepository;
import lld.slackchat.repository.InMemoryUserRepository;
import lld.slackchat.repository.InMemoryWorkspaceRepository;
import lld.slackchat.repository.UserRepository;
import lld.slackchat.repository.WorkspaceRepository;
import lld.slackchat.store.InMemoryMessageStore;
import lld.slackchat.store.MessageStore;
import lld.slackchat.store.UnreadTracker;

/**
 * Default {@link ChatService} implementation wiring together every subsystem in this LLD:
 * repositories, the message store, the delivery strategy, the event bus, rate limiter,
 * presence service, and notification service.
 *
 * <p>Every public method validates preconditions (existence, membership, rate limit) before
 * delegating to the relevant subsystem, so callers get a single, consistent place to look for
 * "what can go wrong and what exception do I get" (README section 8 and 13).
 */
public final class ChatServiceImpl implements ChatService {

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ChannelRepository channelRepository;
    private final MessageStore messageStore;
    private final UnreadTracker unreadTracker;
    private final EventBus eventBus;
    private final PresenceService presenceService;
    private final NotificationService notificationService;
    private final RateLimiter rateLimiter;
    private final ChannelDeliveryPolicy deliveryPolicy;
    private final MentionParser mentionParser;

    public ChatServiceImpl(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            ChannelRepository channelRepository,
            MessageStore messageStore,
            UnreadTracker unreadTracker,
            EventBus eventBus,
            PresenceService presenceService,
            NotificationService notificationService,
            RateLimiter rateLimiter,
            ChannelDeliveryPolicy deliveryPolicy) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository");
        this.channelRepository = Objects.requireNonNull(channelRepository, "channelRepository");
        this.messageStore = Objects.requireNonNull(messageStore, "messageStore");
        this.unreadTracker = Objects.requireNonNull(unreadTracker, "unreadTracker");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.presenceService = Objects.requireNonNull(presenceService, "presenceService");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.deliveryPolicy = Objects.requireNonNull(deliveryPolicy, "deliveryPolicy");
        this.mentionParser = new MentionParser(
                username -> userRepository.findByUsername(username).map(User::id));
    }

    /** Convenience wiring for demos/tests: a fully in-memory stack with sane default policies. */
    public static ChatServiceImpl createDefault() {
        EventBus eventBus = new lld.slackchat.events.InMemoryEventBus();
        return new ChatServiceImpl(
                new InMemoryUserRepository(),
                new InMemoryWorkspaceRepository(),
                new InMemoryChannelRepository(),
                new InMemoryMessageStore(),
                new UnreadTracker(),
                eventBus,
                new PresenceService(eventBus),
                new NotificationService(eventBus),
                new lld.slackchat.ratelimit.TokenBucketRateLimiter(5, 1.0),
                new ChannelDeliveryPolicy(500));
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public PresenceService presenceService() {
        return presenceService;
    }

    public NotificationService notificationService() {
        return notificationService;
    }

    // ---------------------------------------------------------------- users / workspaces

    @Override
    public User createUser(String username, String displayName) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already taken: " + username);
        }
        User user = new User(UserId.generate(), username, displayName, Instant.now());
        return userRepository.save(user);
    }

    @Override
    public Workspace createWorkspace(String name, UserId ownerId) {
        requireUser(ownerId);
        Workspace workspace = new Workspace(WorkspaceId.generate(), name, ownerId, Instant.now());
        return workspaceRepository.save(workspace);
    }

    @Override
    public void addUserToWorkspace(WorkspaceId workspaceId, UserId userId) {
        Workspace workspace = requireWorkspace(workspaceId);
        requireUser(userId);
        workspace.addMember(userId);
    }

    // ---------------------------------------------------------------- channels

    @Override
    public Channel createPublicChannel(WorkspaceId workspaceId, String name, UserId creatorId) {
        Workspace workspace = requireWorkspace(workspaceId);
        requireWorkspaceMember(workspace, creatorId);
        Channel channel = ChannelFactory.createPublicChannel(workspaceId, name, creatorId, Set.of());
        return channelRepository.save(channel);
    }

    @Override
    public Channel createPrivateChannel(
            WorkspaceId workspaceId, String name, UserId creatorId, Set<UserId> initialMembers) {
        Workspace workspace = requireWorkspace(workspaceId);
        requireWorkspaceMember(workspace, creatorId);
        for (UserId memberId : initialMembers) {
            requireWorkspaceMember(workspace, memberId);
        }
        Channel channel = ChannelFactory.createPrivateChannel(workspaceId, name, creatorId, initialMembers);
        return channelRepository.save(channel);
    }

    @Override
    public Channel getOrCreateDirectMessage(WorkspaceId workspaceId, UserId userA, UserId userB) {
        Workspace workspace = requireWorkspace(workspaceId);
        requireWorkspaceMember(workspace, userA);
        requireWorkspaceMember(workspace, userB);
        return channelRepository
                .findDirectMessage(workspaceId, userA, userB)
                .orElseGet(() -> channelRepository.save(ChannelFactory.createDirectMessage(workspaceId, userA, userB)));
    }

    @Override
    public void joinChannel(ChannelId channelId, UserId userId) {
        Channel channel = requireChannel(channelId);
        if (channel.type() != ChannelType.PUBLIC) {
            throw new InvalidChannelOperationException(
                    "Only public channels can be joined directly; channel " + channelId
                            + " is " + channel.type());
        }
        Workspace workspace = requireWorkspace(channel.workspaceId());
        requireWorkspaceMember(workspace, userId);
        channel.addMember(userId);
    }

    @Override
    public void leaveChannel(ChannelId channelId, UserId userId) {
        Channel channel = requireChannel(channelId);
        channel.removeMember(userId);
    }

    // ---------------------------------------------------------------- messaging

    @Override
    public Message sendMessage(ChannelId channelId, UserId senderId, String content) {
        Channel channel = requireChannel(channelId);
        requireChannelMember(channel, senderId);

        if (!rateLimiter.tryAcquire(senderId.value())) {
            throw new RateLimitExceededException("Sender " + senderId + " is sending messages too quickly");
        }

        Set<UserId> mentions = new HashSet<>(mentionParser.parseMentions(content));
        mentions.remove(senderId); // a user mentioning themselves does not generate a notification

        Message message = messageStore.append(channelId, senderId, content, mentions);

        ChannelDeliveryStrategy strategy = deliveryPolicy.strategyFor(channel);
        strategy.deliver(channel, message, eventBus);

        publishMentionEvents(channel, message, mentions);
        return message;
    }

    @Override
    public Message editMessage(ChannelId channelId, MessageId messageId, UserId editorId, String newContent) {
        Channel channel = requireChannel(channelId);
        Message message = requireMessage(channelId, messageId);

        Set<UserId> newMentions = new HashSet<>(mentionParser.parseMentions(newContent));
        newMentions.remove(editorId);

        Set<UserId> previousMentions = message.mentionedUserIds();
        message.edit(editorId, newContent, newMentions);

        eventBus.publish(
                "channel:" + channelId,
                new ChatEvent.MessageEditedEvent(channelId, messageId, editorId, Instant.now()));

        Set<UserId> newlyMentioned = new HashSet<>(newMentions);
        newlyMentioned.removeAll(previousMentions);
        publishMentionEvents(channel, message, newlyMentioned);
        return message;
    }

    @Override
    public void deleteMessage(ChannelId channelId, MessageId messageId, UserId requesterId) {
        requireChannel(channelId);
        Message message = requireMessage(channelId, messageId);
        message.delete(requesterId);
        eventBus.publish(
                "channel:" + channelId,
                new ChatEvent.MessageDeletedEvent(channelId, messageId, requesterId, Instant.now()));
    }

    @Override
    public MessagePage getHistory(ChannelId channelId, UserId requesterId, PageRequest pageRequest) {
        Channel channel = requireChannel(channelId);
        requireChannelMember(channel, requesterId);
        return messageStore.getPage(channelId, pageRequest);
    }

    @Override
    public void markRead(ChannelId channelId, UserId userId, long uptoSequence) {
        Channel channel = requireChannel(channelId);
        requireChannelMember(channel, userId);
        long latest = messageStore.latestSequence(channelId);
        if (uptoSequence < 0 || uptoSequence > latest) {
            throw new IllegalArgumentException(
                    "uptoSequence " + uptoSequence + " is out of range [0, " + latest + "] for channel " + channelId);
        }
        unreadTracker.markRead(channelId, userId, uptoSequence);
    }

    @Override
    public long getUnreadCount(ChannelId channelId, UserId userId) {
        requireChannel(channelId);
        long latest = messageStore.latestSequence(channelId);
        return unreadTracker.unreadCount(channelId, userId, latest);
    }

    @Override
    public void setPresence(UserId userId, PresenceStatus status) {
        requireUser(userId);
        presenceService.setStatus(userId, status);
    }

    // ---------------------------------------------------------------- helpers

    private void publishMentionEvents(Channel channel, Message message, Set<UserId> mentionedUserIds) {
        Instant now = Instant.now();
        for (UserId mentionedId : mentionedUserIds) {
            if (!channel.isMember(mentionedId)) {
                continue; // do not notify users outside the channel about mentions they cannot see
            }
            eventBus.publish(
                    "mention",
                    new ChatEvent.MentionEvent(channel.id(), message.id(), mentionedId, message.senderId(), now));
        }
    }

    private User requireUser(UserId userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("No such user: " + userId));
    }

    private Workspace requireWorkspace(WorkspaceId workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException("No such workspace: " + workspaceId));
    }

    private Channel requireChannel(ChannelId channelId) {
        return channelRepository.findById(channelId)
                .orElseThrow(() -> new ChannelNotFoundException("No such channel: " + channelId));
    }

    private Message requireMessage(ChannelId channelId, MessageId messageId) {
        Message message = messageStore.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException("No such message: " + messageId));
        if (!message.channelId().equals(channelId)) {
            throw new MessageNotFoundException("Message " + messageId + " does not belong to channel " + channelId);
        }
        return message;
    }

    private void requireWorkspaceMember(Workspace workspace, UserId userId) {
        requireUser(userId);
        if (!workspace.isMember(userId)) {
            throw new NotAChannelMemberException(
                    "User " + userId + " is not a member of workspace " + workspace.id());
        }
    }

    private void requireChannelMember(Channel channel, UserId userId) {
        if (!channel.isMember(userId)) {
            throw new NotAChannelMemberException(
                    "User " + userId + " is not a member of channel " + channel.id());
        }
    }
}
