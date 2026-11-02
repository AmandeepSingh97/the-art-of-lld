package lld.slackchat.exception;

/** Thrown when a referenced {@code WorkspaceId} does not exist in the workspace repository. */
public class WorkspaceNotFoundException extends ChatException {
    public WorkspaceNotFoundException(String message) {
        super(message);
    }
}
