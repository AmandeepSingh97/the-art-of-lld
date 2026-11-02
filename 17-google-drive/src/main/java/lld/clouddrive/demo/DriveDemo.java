package lld.clouddrive.demo;

import lld.clouddrive.model.DriveNode;
import lld.clouddrive.model.File;
import lld.clouddrive.model.Folder;
import lld.clouddrive.model.Role;
import lld.clouddrive.model.TrashRecord;
import lld.clouddrive.model.User;
import lld.clouddrive.notification.NotificationService;
import lld.clouddrive.permission.InheritedAclPermissionStrategy;
import lld.clouddrive.permission.PermissionService;
import lld.clouddrive.quota.QuotaManager;
import lld.clouddrive.service.DriveNodeFactory;
import lld.clouddrive.service.DriveService;
import lld.clouddrive.service.SearchService;
import lld.clouddrive.service.TrashService;
import lld.clouddrive.store.BlobStore;
import lld.clouddrive.store.InMemoryBlobStore;
import lld.clouddrive.store.InMemoryNodeRepository;
import lld.clouddrive.store.NodeRepository;
import lld.clouddrive.store.Sha256HashingStrategy;
import lld.clouddrive.util.IdGenerator;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Runnable, end-to-end walkthrough of every major feature: composite tree browsing, versioning,
 * sharing/permission inheritance, quota enforcement, content-hash deduplication, trash/restore,
 * and search. Intended to be read top-to-bottom as a narrated demo, the same way you would talk
 * through the design in an interview.
 */
public final class DriveDemo {

    public static void main(String[] args) {
        Clock clock = Clock.systemUTC();
        NodeRepository nodeRepository = new InMemoryNodeRepository();
        BlobStore blobStore = new InMemoryBlobStore(new Sha256HashingStrategy());
        QuotaManager quotaManager = new QuotaManager(10_000_000L);
        PermissionService permissionService = new PermissionService(new InheritedAclPermissionStrategy());
        SearchService searchService = new SearchService(permissionService);
        TrashService trashService = new TrashService(blobStore, quotaManager, nodeRepository,
                searchService, Duration.ofDays(30));
        NotificationService notificationService = new NotificationService();
        notificationService.subscribe(event -> System.out.println("  [event] " + event));
        DriveNodeFactory nodeFactory = new DriveNodeFactory(new IdGenerator("node"), clock);

        DriveService drive = new DriveService(nodeRepository, blobStore, quotaManager, permissionService,
                searchService, trashService, notificationService, nodeFactory, clock);

        System.out.println("== 1. Register users with per-user quota ==");
        User alice = new User("u-alice", "Alice", "alice@example.com");
        User bob = new User("u-bob", "Bob", "bob@example.com");
        Folder aliceRoot = drive.registerUser(alice, 1_000_000L); // ~1 MB quota
        Folder bobRoot = drive.registerUser(bob, 1_000_000L);
        System.out.println("Alice root=" + aliceRoot.getId() + ", Bob root=" + bobRoot.getId());

        System.out.println("\n== 2. Composite tree: folders within folders ==");
        Folder reports = drive.createFolder(alice.getId(), aliceRoot.getId(), "Reports");
        Folder q1 = drive.createFolder(alice.getId(), reports.getId(), "Q1");
        System.out.println("Created " + reports.getName() + "/" + q1.getName());

        System.out.println("\n== 3. Upload a file (creates version 1) ==");
        byte[] v1Content = "Quarterly numbers v1".getBytes(StandardCharsets.UTF_8);
        File budget = drive.createFile(alice.getId(), q1.getId(), "budget.csv", v1Content);
        System.out.println("Created file " + budget.getName() + " id=" + budget.getId());
        System.out.println("Used quota for Alice: " + drive.getQuotaUsedBytes(alice.getId()) + " bytes");

        System.out.println("\n== 4. Edit the file: Memento-style new version, old version intact ==");
        byte[] v2Content = "Quarterly numbers v2 - corrected".getBytes(StandardCharsets.UTF_8);
        drive.uploadNewVersion(alice.getId(), budget.getId(), v2Content);
        System.out.println("Versions now: " + drive.listVersions(alice.getId(), budget.getId()));
        System.out.println("Download v1 -> " + new String(
                drive.downloadVersion(alice.getId(), budget.getId(), 1), StandardCharsets.UTF_8));
        System.out.println("Download latest -> " + new String(
                drive.downloadLatest(alice.getId(), budget.getId()), StandardCharsets.UTF_8));

        System.out.println("\n== 5. Share the Q1 folder with Bob as VIEWER; permission inherits down ==");
        drive.share(alice.getId(), q1.getId(), bob.getId(), Role.VIEWER);
        System.out.println("Bob's effective role on the folder: " + drive.effectivePermission(q1.getId(), bob.getId()));
        System.out.println("Bob's effective role on budget.csv (inherited): "
                + drive.effectivePermission(budget.getId(), bob.getId()));
        System.out.println("Bob can read latest content -> "
                + new String(drive.downloadLatest(bob.getId(), budget.getId()), StandardCharsets.UTF_8));
        try {
            drive.uploadNewVersion(bob.getId(), budget.getId(), "Bob trying to edit".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("Bob cannot edit (VIEWER only): " + e.getMessage());
        }

        System.out.println("\n== 6. Deduplication: Bob uploads byte-identical content elsewhere ==");
        Folder bobFolder = drive.createFolder(bob.getId(), bobRoot.getId(), "Shared Copies");
        File bobCopy = drive.createFile(bob.getId(), bobFolder.getId(), "same-content.csv", v2Content);
        System.out.println("Physical blob count (should be 2: v1 content + v2 content, shared by Alice & Bob): "
                + blobStore.blobCount());
        System.out.println("Bob's copy and Alice's v2 share the same content hash: "
                + bobCopy.getCurrentVersion().getContentHash().equals(
                        budget.getVersion(2).getContentHash()));

        System.out.println("\n== 7. Quota exhaustion ==");
        try {
            byte[] huge = new byte[2_000_000];
            drive.createFile(alice.getId(), q1.getId(), "too-big.bin", huge);
        } catch (Exception e) {
            System.out.println("Upload correctly rejected: " + e.getMessage());
        }

        System.out.println("\n== 8. Trash / restore ==");
        drive.moveToTrash(alice.getId(), budget.getId());
        System.out.println("Trash contents: " + drive.listTrash().stream().map(TrashRecord::getNodeId).toList());
        System.out.println("Trashed node still readable by id (content preserved until purge): "
                + new String(drive.downloadLatest(bob.getId(), budget.getId()), StandardCharsets.UTF_8));
        drive.restoreFromTrash(alice.getId(), budget.getId());
        System.out.println("Restored. Q1 children now: "
                + drive.listChildren(alice.getId(), q1.getId()).stream().map(DriveNode::getName).toList());

        System.out.println("\n== 9. Permanent purge frees quota + decrements blob ref count ==");
        long usedBefore = drive.getQuotaUsedBytes(alice.getId());
        int blobsBefore = blobStore.blobCount();
        drive.moveToTrash(alice.getId(), budget.getId());
        drive.purgePermanently(alice.getId(), budget.getId());
        System.out.println("Alice used bytes before=" + usedBefore + " after=" + drive.getQuotaUsedBytes(alice.getId()));
        System.out.println("Blob count before=" + blobsBefore + " after=" + blobStore.blobCount()
                + " (v2 content blob survives because Bob's copy still references it)");

        System.out.println("\n== 10. Search by name ==");
        Folder notes = drive.createFolder(alice.getId(), aliceRoot.getId(), "Meeting Notes 2024");
        List<DriveNode> results = drive.search(alice.getId(),
                new SearchService.SearchQuery("meeting", null, null, null));
        System.out.println("Search 'meeting' -> " + results.stream().map(DriveNode::getName).toList());

        System.out.println("\n== 11. Retention sweep (nothing expired yet with default 30-day window) ==");
        List<String> swept = drive.runTrashRetentionSweep(Instant.now());
        System.out.println("Purged by sweep: " + swept);

        System.out.println("\nDemo complete.");
    }

    private DriveDemo() {
    }
}
