package lld.clouddrive.notification;

/**
 * Observer interface for drive events. Production use case: a sync client (desktop agent,
 * mobile app) subscribes to be told "something changed" instead of polling the whole tree.
 * Intentionally NOT wired into the critical path of any quota/permission/dedup decision --
 * notification delivery failures must never be able to block or corrupt a core operation.
 */
public interface FileChangeListener {
    void onEvent(FileChangeEvent event);
}
