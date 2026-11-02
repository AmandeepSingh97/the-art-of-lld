package lld.filesystem;

import lld.filesystem.exception.FileAlreadyExistsException;
import lld.filesystem.exception.NotADirectoryException;
import lld.filesystem.exception.NotAFileException;
import lld.filesystem.exception.PathNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers path resolution (absolute/relative, "." and ".."), mkdir, cd/pwd, touch, cat/write and
 * ls -- the non-recursive, single-node surface of the file system.
 */
class FileSystemBasicsTest {

    private FileSystem fs;
    private Session session;

    @BeforeEach
    void setUp() {
        fs = new FileSystem();
        session = fs.newSession();
    }

    @Test
    void rootIsInitialWorkingDirectory() {
        assertEquals("/", fs.pwd(session));
    }

    @Test
    void mkdirCreatesSingleDirectory() {
        fs.mkdir(session, "/a", false);
        assertEquals(1, fs.ls(session, "/").size());
        assertEquals("a", fs.ls(session, "/").get(0).getName());
    }

    @Test
    void mkdirWithoutParentFlagFailsWhenParentMissing() {
        assertThrows(PathNotFoundException.class, () -> fs.mkdir(session, "/a/b/c", false));
    }

    @Test
    void mkdirWithParentFlagCreatesWholeChain() {
        fs.mkdir(session, "/a/b/c", true);
        fs.cd(session, "/a/b/c");
        assertEquals("/a/b/c", fs.pwd(session));
    }

    @Test
    void mkdirOnExistingDirectoryWithoutParentFlagThrows() {
        fs.mkdir(session, "/a", false);
        assertThrows(FileAlreadyExistsException.class, () -> fs.mkdir(session, "/a", false));
    }

    @Test
    void mkdirThroughAFileSegmentThrowsNotADirectory() {
        fs.touch(session, "/f");
        assertThrows(NotADirectoryException.class, () -> fs.mkdir(session, "/f/child", true));
    }

    @Test
    void cdSupportsRelativeDotDotAndDotSegments() {
        fs.mkdir(session, "/a/b", true);
        fs.cd(session, "/a/b");
        fs.cd(session, "../../a/./b/..");
        assertEquals("/a", fs.pwd(session));
    }

    @Test
    void cdBeyondRootClampsAtRoot() {
        fs.cd(session, "../../../../..");
        assertEquals("/", fs.pwd(session));
    }

    @Test
    void cdOnFileThrowsNotADirectory() {
        fs.touch(session, "/f");
        assertThrows(NotADirectoryException.class, () -> fs.cd(session, "/f"));
    }

    @Test
    void touchCreatesEmptyFileAndIsIdempotent() {
        fs.touch(session, "/note.txt");
        fs.touch(session, "/note.txt"); // should not throw
        assertEquals("", fs.cat(session, "/note.txt"));
    }

    @Test
    void touchOnExistingDirectoryThrowsNotAFile() {
        fs.mkdir(session, "/dir", false);
        assertThrows(NotAFileException.class, () -> fs.touch(session, "/dir"));
    }

    @Test
    void writeThenCatRoundTripsContent() {
        fs.write(session, "/note.txt", "hello", false);
        assertEquals("hello", fs.cat(session, "/note.txt"));
    }

    @Test
    void writeCreatesFileIfMissing() {
        fs.write(session, "/new.txt", "data", false);
        assertEquals("data", fs.cat(session, "/new.txt"));
    }

    @Test
    void appendWriteConcatenatesContent() {
        fs.write(session, "/note.txt", "hello", false);
        fs.write(session, "/note.txt", " world", true);
        assertEquals("hello world", fs.cat(session, "/note.txt"));
    }

    @Test
    void catOnMissingPathThrowsPathNotFound() {
        assertThrows(PathNotFoundException.class, () -> fs.cat(session, "/missing.txt"));
    }

    @Test
    void catOnDirectoryThrowsNotAFile() {
        fs.mkdir(session, "/dir", false);
        assertThrows(NotAFileException.class, () -> fs.cat(session, "/dir"));
    }

    @Test
    void lsOnRelativePathUsesCwd() {
        fs.mkdir(session, "/a/b", true);
        fs.cd(session, "/a");
        List<FileSystemEntry> entries = fs.ls(session, "b");
        assertTrue(entries.isEmpty());
    }

    @Test
    void lsListsFilesAndDirectoriesTogether() {
        fs.mkdir(session, "/a/dir", true);
        fs.touch(session, "/a/file.txt");
        assertEquals(2, fs.ls(session, "/a").size());
    }
}
