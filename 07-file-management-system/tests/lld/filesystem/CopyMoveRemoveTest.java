package lld.filesystem;

import lld.filesystem.exception.DirectoryNotEmptyException;
import lld.filesystem.exception.FileAlreadyExistsException;
import lld.filesystem.exception.InvalidOperationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the recursive, two-node operations: cp, mv and rm -- including name-collision handling
 * and cycle prevention, which are the parts of this problem that are easy to get subtly wrong.
 */
class CopyMoveRemoveTest {

    private FileSystem fs;
    private Session session;

    @BeforeEach
    void setUp() {
        fs = new FileSystem();
        session = fs.newSession();
        fs.mkdir(session, "/src/sub", true);
        fs.write(session, "/src/a.txt", "alpha", false);
        fs.write(session, "/src/sub/b.txt", "beta", false);
    }

    // ---------------- cp ----------------

    @Test
    void cpFileCopiesContentIndependently() {
        fs.cp(session, "/src/a.txt", "/src/a-copy.txt", false, false);
        assertEquals("alpha", fs.cat(session, "/src/a-copy.txt"));

        fs.write(session, "/src/a.txt", "changed", false);
        assertEquals("alpha", fs.cat(session, "/src/a-copy.txt"), "copy must be independent of source");
    }

    @Test
    void cpDirectoryWithoutRecursiveFlagThrows() {
        assertThrows(InvalidOperationException.class, () -> fs.cp(session, "/src", "/dest", false, false));
    }

    @Test
    void cpDirectoryRecursivelyCopiesWholeSubtree() {
        fs.cp(session, "/src", "/dest", true, false);
        assertEquals("alpha", fs.cat(session, "/dest/a.txt"));
        assertEquals("beta", fs.cat(session, "/dest/sub/b.txt"));
        // original subtree untouched
        assertEquals("alpha", fs.cat(session, "/src/a.txt"));
    }

    @Test
    void cpIntoExistingDirectoryCopiesInsideIt() {
        fs.mkdir(session, "/target", false);
        fs.cp(session, "/src", "/target", true, false);
        assertEquals("alpha", fs.cat(session, "/target/src/a.txt"));
    }

    @Test
    void cpOntoExistingFileWithoutOverwriteThrows() {
        fs.touch(session, "/src/a-copy.txt");
        assertThrows(FileAlreadyExistsException.class,
                () -> fs.cp(session, "/src/a.txt", "/src/a-copy.txt", false, false));
    }

    @Test
    void cpOntoExistingFileWithOverwriteReplacesContent() {
        fs.write(session, "/existing.txt", "old", false);
        fs.cp(session, "/src/a.txt", "/existing.txt", false, true);
        assertEquals("alpha", fs.cat(session, "/existing.txt"));
    }

    @Test
    void cpDirectoryIntoOwnDescendantTerminatesAndIsConsistent() {
        // Copying /src into /src/sub must not infinitely recurse: the snapshot taken at call
        // time does not include nodes created by the copy itself.
        fs.cp(session, "/src", "/src/sub/src-copy", true, false);
        assertEquals("alpha", fs.cat(session, "/src/sub/src-copy/a.txt"));
        assertEquals("beta", fs.cat(session, "/src/sub/src-copy/sub/b.txt"));
    }

    // ---------------- mv ----------------

    @Test
    void mvRenamesWithinSameDirectory() {
        fs.mv(session, "/src/a.txt", "/src/renamed.txt", false);
        assertEquals("alpha", fs.cat(session, "/src/renamed.txt"));
    }

    @Test
    void mvMovesAcrossDirectoriesPreservingContent() {
        fs.mkdir(session, "/other", false);
        fs.mv(session, "/src/a.txt", "/other/a.txt", false);
        assertEquals("alpha", fs.cat(session, "/other/a.txt"));
    }

    @Test
    void mvIntoExistingDirectoryMovesInsideIt() {
        fs.mkdir(session, "/target", false);
        fs.mv(session, "/src", "/target", false);
        assertEquals("alpha", fs.cat(session, "/target/src/a.txt"));
    }

    @Test
    void mvDirectoryIntoOwnDescendantThrowsInvalidOperation() {
        assertThrows(InvalidOperationException.class, () -> fs.mv(session, "/src", "/src/sub/nested", false));
    }

    @Test
    void mvDirectoryIntoItselfThrowsInvalidOperation() {
        assertThrows(InvalidOperationException.class, () -> fs.mv(session, "/src", "/src", false));
    }

    @Test
    void mvOntoExistingFileWithoutForceThrows() {
        fs.touch(session, "/other.txt");
        assertThrows(FileAlreadyExistsException.class, () -> fs.mv(session, "/src/a.txt", "/other.txt", false));
    }

    @Test
    void mvOntoExistingFileWithForceOverwrites() {
        fs.write(session, "/other.txt", "old", false);
        fs.mv(session, "/src/a.txt", "/other.txt", true);
        assertEquals("alpha", fs.cat(session, "/other.txt"));
    }

    @Test
    void mvCannotMoveAncestorOfCurrentWorkingDirectory() {
        fs.cd(session, "/src/sub");
        assertThrows(InvalidOperationException.class, () -> fs.mv(session, "/src", "/elsewhere", false));
    }

    // ---------------- rm ----------------

    @Test
    void rmFileRemovesIt() {
        fs.rm(session, "/src/a.txt", false, false);
        assertTrue(fs.ls(session, "/src").stream().noneMatch(e -> e.getName().equals("a.txt")));
    }

    @Test
    void rmNonEmptyDirectoryWithoutRecursiveThrows() {
        assertThrows(DirectoryNotEmptyException.class, () -> fs.rm(session, "/src", false, false));
    }

    @Test
    void rmRecursiveRemovesWholeSubtree() {
        fs.rm(session, "/src", true, false);
        assertTrue(fs.ls(session, "/").isEmpty());
    }

    @Test
    void rmMissingPathWithoutForceThrows() {
        assertThrows(lld.filesystem.exception.PathNotFoundException.class,
                () -> fs.rm(session, "/does-not-exist", false, false));
    }

    @Test
    void rmMissingPathWithForceIsNoOp() {
        fs.rm(session, "/does-not-exist", true, true);
        // no exception
    }

    @Test
    void rmCannotRemoveAncestorOfCurrentWorkingDirectory() {
        fs.cd(session, "/src/sub");
        assertThrows(InvalidOperationException.class, () -> fs.rm(session, "/src", true, false));
    }

    @Test
    void rmRootThrowsInvalidOperation() {
        assertThrows(InvalidOperationException.class, () -> fs.rm(session, "/", true, false));
    }
}
