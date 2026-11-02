package lld.clouddrive.notification;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal Observer subject: fan-out of {@link FileChangeEvent}s to subscribed
 * {@link FileChangeListener}s. Uses {@link CopyOnWriteArrayList} because subscription changes
 * are rare and publishing (iteration) is comparatively frequent and must never block on a
 * concurrent subscribe/unsubscribe.
 *
 * <p>Publishing is synchronous and best-effort: a throwing listener is isolated (caught and
 * ignored, in production logged) so one broken subscriber cannot break uploads for everyone else.
 */
public final class NotificationService {

    private final List<FileChangeListener> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(FileChangeListener listener) {
        listeners.add(listener);
    }

    public void unsubscribe(FileChangeListener listener) {
        listeners.remove(listener);
    }

    public void publish(FileChangeEvent event) {
        for (FileChangeListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ignored) {
                // A misbehaving subscriber must not be able to fail the triggering operation.
            }
        }
    }
}
