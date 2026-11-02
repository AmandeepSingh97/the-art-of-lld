package lld.filesystem.command;

import lld.filesystem.FileSystem;
import lld.filesystem.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the Command pattern end-to-end through {@link Shell}: tokenization (including
 * quoted arguments), dispatch, and that a {@link lld.filesystem.exception.FileSystemException}
 * thrown deep inside {@link FileSystem} is surfaced as a failed {@link CommandResult} rather
 * than propagating out of {@code execute()}.
 */
class ShellCommandTest {

    private Shell shell;

    @BeforeEach
    void setUp() {
        FileSystem fileSystem = new FileSystem();
        Session session = fileSystem.newSession();
        shell = Shell.withDefaultCommands(fileSystem, session);
    }

    @Test
    void mkdirThenLsShowsNewDirectory() {
        assertTrue(shell.execute("mkdir /a").isSuccess());
        CommandResult ls = shell.execute("ls /");
        assertEquals("a/", ls.getOutput());
    }

    @Test
    void quotedArgumentIsPassedAsOneToken() {
        shell.execute("touch /note.txt");
        assertTrue(shell.execute("write /note.txt \"hello world\"").isSuccess());
        assertEquals("hello world", shell.execute("cat /note.txt").getOutput());
    }

    @Test
    void unknownCommandFails() {
        CommandResult result = shell.execute("frobnicate /a");
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("command not found"));
    }

    @Test
    void fileSystemExceptionIsConvertedToFailedResultNotThrown() {
        CommandResult result = shell.execute("cat /does-not-exist.txt");
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("No such file or directory"));
    }

    @Test
    void cpWithoutRecursiveFlagOnDirectoryFails() {
        shell.execute("mkdir /a");
        CommandResult result = shell.execute("cp /a /b");
        assertFalse(result.isSuccess());
    }

    @Test
    void cpWithRecursiveFlagSucceeds() {
        shell.execute("mkdir /a");
        shell.execute("write /a/f.txt hello");
        assertTrue(shell.execute("cp -r /a /b").isSuccess());
        assertEquals("hello", shell.execute("cat /b/f.txt").getOutput());
    }

    @Test
    void emptyCommandLineIsANoOp() {
        CommandResult result = shell.execute("   ");
        assertTrue(result.isSuccess());
    }

    @Test
    void rmWithShorthandFlagsRemovesNonEmptyDirectory() {
        shell.execute("mkdir /a");
        shell.execute("touch /a/f.txt");
        assertTrue(shell.execute("rm -rf /a").isSuccess());
        assertEquals("", shell.execute("ls /").getOutput());
    }
}
