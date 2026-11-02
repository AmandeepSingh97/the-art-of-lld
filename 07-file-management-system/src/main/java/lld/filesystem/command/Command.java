package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.Session;

/**
 * Command pattern: every shell verb (mkdir, cd, pwd, ls, cp, mv, rm, touch, cat, write) is a
 * small, independently testable object implementing this interface, rather than one large
 * {@code switch} inside a God "Shell" class. This also makes adding a new verb (e.g. {@code find}
 * or {@code stat}) an open/closed-friendly change: implement {@link Command}, register it with
 * {@link Shell}, done -- no existing command's code changes.
 *
 * <p>Implementations must not throw checked exceptions; any {@link lld.filesystem.exception.FileSystemException}
 * raised by the {@link FileSystem} facade is caught by {@link Shell} and converted into a failed
 * {@link CommandResult}, so individual commands are free to just call the facade directly.</p>
 */
public interface Command {

    /** The verb this command answers to, e.g. {@code "mkdir"}. Used as the {@link Shell} registry key. */
    String name();

    /**
     * Executes this command.
     *
     * @param fileSystem the shared, possibly multi-session file system
     * @param session    the invoking session (carries the current working directory)
     * @param args       command-line arguments, not including the verb itself
     */
    CommandResult execute(FileSystem fileSystem, Session session, String[] args);
}
