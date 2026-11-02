package lld.slackchat.notification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lld.slackchat.events.ChatEvent;
import lld.slackchat.events.EventBus;
import lld.slackchat.events.EventListener;
import lld.slackchat.model.UserId;

/**
 * Observer that listens for {@link ChatEvent.MentionEvent}s on the {@code "mention"} topic and
 * accumulates per-user notification inboxes.
 *
 * <p>This class is itself just another {@link EventListener} subscribed through the same
 * {@link EventBus} used for message delivery and presence -- mentions are not a special
 * side-channel, they are regular events consumed by a dedicated observer, which keeps the
 * Observer pattern applied uniformly rather than special-cased per feature.
 *
 * <p><b>Thread-safety:</b> {@code inboxes} is a {@link ConcurrentHashMap} of
 * {@link CopyOnWriteArrayList}s. Appends (on every mention) are more frequent than reads
 * (user opens notification tray), but both must never observe a torn/partial list; CoW lists
 * give that for free at the cost of an O(n) copy per append, which is acceptable given
 * realistic per-user notification volumes (hundreds, not millions).
 */
public final class NotificationService implements EventListener {

    private final Map<UserId, CopyOnWriteArrayList<Notification>> inboxes = new ConcurrentHashMap<>();

    public NotificationService(EventBus eventBus) {
        Objects.requireNonNull(eventBus, "eventBus");
        eventBus.subscribe("mention", this);
    }

    @Override
    public void onEvent(ChatEvent event) {
        if (event instanceof ChatEvent.MentionEvent mentionEvent) {
            Notification notification = new Notification(
                    mentionEvent.channelId(),
                    mentionEvent.messageId(),
                    mentionEvent.mentionedUserId(),
                    mentionEvent.authorId(),
                    Instant.now());
            inboxes.computeIfAbsent(mentionEvent.mentionedUserId(), u -> new CopyOnWriteArrayList<>())
                    .add(notification);
        }
    }

    /** All notifications ever received by {@code userId}, oldest first. */
    public List<Notification> notificationsFor(UserId userId) {
        return List.copyOf(inboxes.getOrDefault(userId, new CopyOnWriteArrayList<>()));
    }

    public int unreadNotificationCount(UserId userId) {
        return notificationsFor(userId).size();
    }
}
