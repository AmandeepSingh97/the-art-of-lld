package lld.clouddrive.service;

import lld.clouddrive.exception.AccessDeniedException;
import lld.clouddrive.exception.DuplicateNameException;
import lld.clouddrive.exception.InvalidOperationException;
import lld.clouddrive.exception.QuotaExceededException;
import lld.clouddrive.model.DriveNode;
import lld.clouddrive.model.File;
import lld.clouddrive.model.FileVersion;
import lld.clouddrive.model.Folder;
import lld.clouddrive.model.Role;
import lld.clouddrive.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end behavioral tests of {@link DriveService} covering composite tree navigation,
 * versioning, sharing/permission inheritance, deduplication, trash/restore, and search -- i.e.
 * every functional requirement from the README exercised through the public facade, the same
 * surface a real caller would use.
 */
class DriveServiceTest {

    private DriveServiceTestSupport harness;
    private User alice;
    private User bob;
    private Folder aliceRoot;
    private Folder bobRoot;

    @BeforeEach
    void setUp() {
        harness = new DriveServiceTestSupport(1_000L, Duration.ofDays(30));
        alice = new User("alice", "Alice", "alice@test.io");
        bob = new User("bob", "Bob", "bob@test.io");
        aliceRoot = harness.driveService.registerUser(alice, 1_000L);
        bobRoot = harness.driveService.registerUser(bob, 1_000L);
    }

    @Test
    void createsNestedFoldersAndListsChildren() {
        Folder work = harness.driveService.createFolder(alice.getId(), aliceRoot.getId(), "Work");
        Folder projectX = harness.driveService.createFolder(alice.getId(), work.getId(), "ProjectX");

        List<DriveNode> rootChildren = List.copyOf(harness.driveService.listChildren(alice.getId(), aliceRoot.getId()));
        assertEquals(1, rootChildren.size());
        assertEquals("Work", rootChildren.get(0).getName());

        List<DriveNode> workChildren = List.copyOf(harness.driveService.listChildren(alice.getId(), work.getId()));
        assertEquals("ProjectX", workChildren.get(0).getName());
        assertTrue(projectX.isFolder());
    }

    @Test
    void duplicateSiblingNameIsRejected() {
        harness.driveService.createFolder(alice.getId(), aliceRoot.getId(), "Docs");
        assertThrows(DuplicateNameException.class,
                () -> harness.driveService.createFolder(alice.getId(), aliceRoot.getId(), "Docs"));
    }

    @Test
    void uploadingNewVersionPreservesOlderVersionsImmutably() {
        File file = harness.driveService.createFile(alice.getId(), aliceRoot.getId(), "notes.txt",
                "version one".getBytes(StandardCharsets.UTF_8));
        harness.driveService.uploadNewVersion(alice.getId(), file.getId(), "version two".getBytes(StandardCharsets.UTF_8));

        List<FileVersion> versions = harness.driveService.listVersions(alice.getId(), file.getId());
        assertEquals(2, versions.size());
        assertEquals("version one", new String(
                harness.driveService.downloadVersion(alice.getId(), file.getId(), 1), StandardCharsets.UTF_8));
        assertEquals("version two", new String(
                harness.driveService.downloadLatest(alice.getId(), file.getId()), StandardCharsets.UTF_8));
    }

    @Test
    void sharingAFolderGrantsInheritedAccessToDescendantFiles() {
        Folder shared = harness.driveService.createFolder(alice.getId(), aliceRoot.getId(), "Shared");
        File file = harness.driveService.createFile(alice.getId(), shared.getId(), "report.csv",
                "data".getBytes(StandardCharsets.UTF_8));

        assertEquals(Role.NONE, harness.driveService.effectivePermission(file.getId(), bob.getId()));

        harness.driveService.share(alice.getId(), shared.getId(), bob.getId(), Role.VIEWER);

        assertEquals(Role.VIEWER, harness.driveService.effectivePermission(file.getId(), bob.getId()));
        assertEquals("data", new String(
                harness.driveService.downloadLatest(bob.getId(), file.getId()), StandardCharsets.UTF_8));
        assertThrows(AccessDeniedException.class,
                () -> harness.driveService.uploadNewVersion(bob.getId(), file.getId(), "hack".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void explicitGrantOnNestedNodeOverridesInheritedAncestorGrant() {
        Folder shared = harness.driveService.createFolder(alice.getId(), aliceRoot.getId(), "Shared");
        File file = harness.driveService.createFile(alice.getId(), shared.getId(), "report.csv",
                "data".getBytes(StandardCharsets.UTF_8));

        harness.driveService.share(alice.getId(), shared.getId(), bob.getId(), Role.VIEWER);
        harness.driveService.share(alice.getId(), file.getId(), bob.getId(), Role.EDITOR);

        assertEquals(Role.EDITOR, harness.driveService.effectivePermission(file.getId(), bob.getId()));
        assertEquals(Role.VIEWER, harness.driveService.effectivePermission(shared.getId(), bob.getId()));
    }

    @Test
    void onlyOwnerCanGrantOrRevokeAccess() {
        Folder shared = harness.driveService.createFolder(alice.getId(), aliceRoot.getId(), "Shared");
        harness.driveService.share(alice.getId(), shared.getId(), bob.getId(), Role.EDITOR);

        assertThrows(AccessDeniedException.class,
                () -> harness.driveService.share(bob.getId(), shared.getId(), "carol", Role.VIEWER));
    }

    @Test
    void movingAFolderIntoItsOwnDescendantIsRejected() {
        Folder parent = harness.driveService.createFolder(alice.getId(), aliceRoot.getId(), "Parent");
        Folder child = harness.driveService.createFolder(alice.getId(), parent.getId(), "Child");

        assertThrows(InvalidOperationException.class,
                () -> harness.driveService.move(alice.getId(), parent.getId(), child.getId()));
    }

    @Test
    void uploadBeyondQuotaIsRejectedAndQuotaUnaffected() {
        long before = harness.driveService.getQuotaUsedBytes(alice.getId());
        byte[] tooBig = new byte[2_000];
        assertThrows(QuotaExceededException.class,
                () -> harness.driveService.createFile(alice.getId(), aliceRoot.getId(), "huge.bin", tooBig));
        assertEquals(before, harness.driveService.getQuotaUsedBytes(alice.getId()));
    }

    @Test
    void identicalContentFromTwoUsersIsStoredAsOneBlob() {
        byte[] content = "shared payload".getBytes(StandardCharsets.UTF_8);
        File aFile = harness.driveService.createFile(alice.getId(), aliceRoot.getId(), "a.txt", content);
        int blobsAfterFirst = harness.blobStore.blobCount();
        File bFile = harness.driveService.createFile(bob.getId(), bobRoot.getId(), "b.txt", content);

        assertEquals(blobsAfterFirst, harness.blobStore.blobCount(), "second identical upload must not create a new blob");
        assertEquals(aFile.getCurrentVersion().getContentHash(), bFile.getCurrentVersion().getContentHash());
        // Both users' quotas are charged independently even though only one physical blob exists.
        assertEquals(content.length, harness.driveService.getQuotaUsedBytes(alice.getId()));
        assertEquals(content.length, harness.driveService.getQuotaUsedBytes(bob.getId()));
    }

    @Test
    void trashThenRestoreRoundTripsBackToOriginalLocation() {
        Folder docs = harness.driveService.createFolder(alice.getId(), aliceRoot.getId(), "Docs");
        File file = harness.driveService.createFile(alice.getId(), docs.getId(), "a.txt",
                "x".getBytes(StandardCharsets.UTF_8));

        harness.driveService.moveToTrash(alice.getId(), file.getId());
        assertTrue(harness.driveService.listChildren(alice.getId(), docs.getId()).isEmpty());

        harness.driveService.restoreFromTrash(alice.getId(), file.getId());
        List<DriveNode> children = List.copyOf(harness.driveService.listChildren(alice.getId(), docs.getId()));
        assertEquals(1, children.size());
        assertEquals("a.txt", children.get(0).getName());
    }

    @Test
    void permanentPurgeReleasesQuotaAndBlobReference() {
        byte[] content = "purge me".getBytes(StandardCharsets.UTF_8);
        File file = harness.driveService.createFile(alice.getId(), aliceRoot.getId(), "a.txt", content);
        String hash = file.getCurrentVersion().getContentHash();
        long usedBefore = harness.driveService.getQuotaUsedBytes(alice.getId());

        harness.driveService.moveToTrash(alice.getId(), file.getId());
        harness.driveService.purgePermanently(alice.getId(), file.getId());

        assertEquals(usedBefore - content.length, harness.driveService.getQuotaUsedBytes(alice.getId()));
        assertEquals(null, harness.blobStore.get(hash));
    }

    @Test
    void retentionSweepPurgesOnlyExpiredTrashEntries() {
        File file = harness.driveService.createFile(alice.getId(), aliceRoot.getId(), "a.txt",
                "x".getBytes(StandardCharsets.UTF_8));
        harness.driveService.moveToTrash(alice.getId(), file.getId());

        List<String> sweptTooEarly = harness.driveService.runTrashRetentionSweep(harness.clock.instant());
        assertTrue(sweptTooEarly.isEmpty());

        harness.clock.advance(Duration.ofDays(31));
        List<String> sweptAfterRetention = harness.driveService.runTrashRetentionSweep(harness.clock.instant());
        assertEquals(List.of(file.getId()), sweptAfterRetention);
    }

    @Test
    void searchFindsNodesByNameTokenButRespectsPermissions() {
        harness.driveService.createFolder(alice.getId(), aliceRoot.getId(), "Budget Reports 2024");
        harness.driveService.createFolder(bob.getId(), bobRoot.getId(), "Budget Secrets");

        List<DriveNode> aliceResults = harness.driveService.search(alice.getId(),
                new SearchService.SearchQuery("budget", null, null, null));

        assertEquals(1, aliceResults.size());
        assertEquals("Budget Reports 2024", aliceResults.get(0).getName());
    }
}
