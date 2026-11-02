package lld.clouddrive.service;

import lld.clouddrive.exception.DuplicateNameException;
import lld.clouddrive.exception.InvalidOperationException;
import lld.clouddrive.exception.NodeNotFoundException;
import lld.clouddrive.model.Blob;
import lld.clouddrive.model.DriveNode;
import lld.clouddrive.model.File;
import lld.clouddrive.model.FileVersion;
import lld.clouddrive.model.Folder;
import lld.clouddrive.model.Role;
import lld.clouddrive.model.TrashRecord;
import lld.clouddrive.model.User;
import lld.clouddrive.notification.FileChangeEvent;
import lld.clouddrive.notification.NotificationService;
import lld.clouddrive.permission.PermissionService;
import lld.clouddrive.quota.QuotaManager;
import lld.clouddrive.store.BlobStore;
import lld.clouddrive.store.NodeRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Facade (Facade pattern) over the whole cloud-drive subsystem -- the single entry point every
 * caller (demo code, a future REST controller) uses instead of reaching into
 * {@code BlobStore}/{@code QuotaManager}/{@code PermissionService}/{@code TrashService}
 * individually. This is also where cross-cutting invariants that span more than one
 * collaborator (e.g. "reserve quota before writing the blob", "a folder cannot become its own
 * descendant") are enforced, since no single collaborator owns enough context to enforce them
 * alone.
 *
 * <p><b>Locking strategy (see README section 11 for full justification):</b> a single
 * {@link ReentrantReadWriteLock} ({@link #structuralLock}) protects the SHAPE of the tree
 * (parent/child pointers, which names exist in which folder). Every operation that changes shape
 * (create, rename, move, trash, restore, permanent purge) takes the write lock; operations that
 * only need a stable, non-moving view of the shape (listing children, resolving a path, search
 * navigation) take the read lock. Operations that only touch a single file's content/version
 * history (upload new version, download) take a brief read lock just long enough to resolve the
 * node, then rely on that {@link File}'s own per-file monitor plus the independently
 * thread-safe {@link BlobStore}/{@link QuotaManager} -- they deliberately do NOT hold the
 * structural lock for the (potentially large) content write, so uploads to different files never
 * contend with each other or with unrelated tree browsing.
 */
public final class DriveService {

    private final NodeRepository nodeRepository;
    private final BlobStore blobStore;
    private final QuotaManager quotaManager;
    private final PermissionService permissionService;
    private final SearchService searchService;
    private final TrashService trashService;
    private final NotificationService notificationService;
    private final DriveNodeFactory nodeFactory;
    private final Clock clock;
    private final ReadWriteLock structuralLock = new ReentrantReadWriteLock();

    public DriveService(NodeRepository nodeRepository, BlobStore blobStore, QuotaManager quotaManager,
                         PermissionService permissionService, SearchService searchService,
                         TrashService trashService, NotificationService notificationService,
                         DriveNodeFactory nodeFactory, Clock clock) {
        this.nodeRepository = nodeRepository;
        this.blobStore = blobStore;
        this.quotaManager = quotaManager;
        this.permissionService = permissionService;
        this.searchService = searchService;
        this.trashService = trashService;
        this.notificationService = notificationService;
        this.nodeFactory = nodeFactory;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- user & root management

    public Folder registerUser(User user, long quotaBytes) {
        quotaManager.registerUser(user.getId(), quotaBytes);
        structuralLock.writeLock().lock();
        try {
            Folder root = nodeFactory.newFolder("My Drive", user.getId(), null);
            nodeRepository.save(root);
            searchService.indexNode(root);
            return root;
        } finally {
            structuralLock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------- node lookup

    private DriveNode getNodeOrThrow(String nodeId) {
        return nodeRepository.findById(nodeId).orElseThrow(() -> new NodeNotFoundException(nodeId));
    }

    private Folder getFolderOrThrow(String nodeId) {
        DriveNode node = getNodeOrThrow(nodeId);
        if (!(node instanceof Folder)) {
            throw new InvalidOperationException("Node '" + nodeId + "' is not a folder");
        }
        return (Folder) node;
    }

    private File getFileOrThrow(String nodeId) {
        DriveNode node = getNodeOrThrow(nodeId);
        if (!(node instanceof File)) {
            throw new InvalidOperationException("Node '" + nodeId + "' is not a file");
        }
        return (File) node;
    }

    // --------------------------------------------------------------------------- folder & file creation

    public Folder createFolder(String userId, String parentFolderId, String name) {
        structuralLock.writeLock().lock();
        try {
            Folder parent = getFolderOrThrow(parentFolderId);
            permissionService.requireRole(parent, userId, Role.EDITOR);
            if (parent.hasChildNamed(name)) {
                throw new DuplicateNameException(parentFolderId, name);
            }
            Folder folder = nodeFactory.newFolder(name, userId, parent);
            parent.addChild(folder);
            nodeRepository.save(folder);
            searchService.indexNode(folder);
            notificationService.publish(new FileChangeEvent(
                    FileChangeEvent.Type.CREATED, folder.getId(), userId, clock.instant()));
            return folder;
        } finally {
            structuralLock.writeLock().unlock();
        }
    }

    /** Creates a brand-new file with an initial version (version 1) containing {@code content}. */
    public File createFile(String userId, String parentFolderId, String name, byte[] content) {
        Folder parent;
        structuralLock.readLock().lock();
        try {
            parent = getFolderOrThrow(parentFolderId);
            permissionService.requireRole(parent, userId, Role.EDITOR);
            if (parent.hasChildNamed(name)) {
                throw new DuplicateNameException(parentFolderId, name);
            }
        } finally {
            structuralLock.readLock().unlock();
        }

        // Quota is reserved BEFORE the (comparatively expensive) blob write so a doomed upload
        // fails fast without ever touching the content-addressed store or ref-counting anything.
        quotaManager.reserve(userId, content.length);
        Blob blob = blobStore.storeAndRetain(content);

        structuralLock.writeLock().lock();
        try {
            // Re-check under the write lock: another thread may have created a same-named sibling
            // between our read-lock check above and now (classic check-then-act across a lock
            // upgrade). If so, roll back the quota/blob side effects we already committed.
            if (parent.hasChildNamed(name)) {
                quotaManager.release(userId, content.length);
                blobStore.release(blob.getContentHash());
                throw new DuplicateNameException(parentFolderId, name);
            }
            File file = nodeFactory.newFile(name, userId, parent);
            file.addVersion(new FileVersion(1, blob.getContentHash(), content.length, userId, clock.instant()));
            parent.addChild(file);
            nodeRepository.save(file);
            searchService.indexNode(file);
            notificationService.publish(new FileChangeEvent(
                    FileChangeEvent.Type.CREATED, file.getId(), userId, clock.instant()));
            return file;
        } finally {
            structuralLock.writeLock().unlock();
        }
    }

    /** Appends a new version to an existing file. Requires EDITOR+ on the file. */
    public FileVersion uploadNewVersion(String userId, String fileId, byte[] content) {
        File file;
        structuralLock.readLock().lock();
        try {
            file = getFileOrThrow(fileId);
            permissionService.requireRole(file, userId, Role.EDITOR);
        } finally {
            structuralLock.readLock().unlock();
        }

        quotaManager.reserve(userId, content.length);
        Blob blob = blobStore.storeAndRetain(content);

        synchronized (file) {
            FileVersion version = new FileVersion(
                    file.nextVersionNumber(), blob.getContentHash(), content.length, userId, clock.instant());
            file.addVersion(version);
            notificationService.publish(new FileChangeEvent(
                    FileChangeEvent.Type.NEW_VERSION, file.getId(), userId, clock.instant()));
            return version;
        }
    }

    public byte[] downloadLatest(String userId, String fileId) {
        File file = getFileOrThrow(fileId);
        permissionService.requireRole(file, userId, Role.VIEWER);
        FileVersion version = file.getCurrentVersion();
        if (version == null) {
            throw new InvalidOperationException("File '" + fileId + "' has no versions");
        }
        return blobStore.get(version.getContentHash()).getContent();
    }

    public byte[] downloadVersion(String userId, String fileId, int versionNumber) {
        File file = getFileOrThrow(fileId);
        permissionService.requireRole(file, userId, Role.VIEWER);
        FileVersion version = file.getVersion(versionNumber);
        if (version == null) {
            throw new InvalidOperationException("File '" + fileId + "' has no version " + versionNumber);
        }
        return blobStore.get(version.getContentHash()).getContent();
    }

    public List<FileVersion> listVersions(String userId, String fileId) {
        File file = getFileOrThrow(fileId);
        permissionService.requireRole(file, userId, Role.VIEWER);
        return file.listVersions();
    }

    // --------------------------------------------------------------------------------- rename & move

    public void rename(String userId, String nodeId, String newName) {
        structuralLock.writeLock().lock();
        try {
            DriveNode node = getNodeOrThrow(nodeId);
            permissionService.requireRole(node, userId, Role.EDITOR);
            DriveNodeFactory.validateName(newName);
            Folder parent = node.getParent();
            if (parent != null) {
                if (parent.hasChildNamed(newName)) {
                    throw new DuplicateNameException(parent.getId(), newName);
                }
                parent.removeChildByName(node.getName());
            }
            String oldName = node.getName();
            node.renameInternal(newName);
            if (parent != null) {
                parent.addChild(node);
            }
            searchService.reindexRename(node, oldName);
            notificationService.publish(new FileChangeEvent(
                    FileChangeEvent.Type.RENAMED, nodeId, userId, clock.instant()));
        } finally {
            structuralLock.writeLock().unlock();
        }
    }

    public void move(String userId, String nodeId, String newParentFolderId) {
        structuralLock.writeLock().lock();
        try {
            DriveNode node = getNodeOrThrow(nodeId);
            Folder newParent = getFolderOrThrow(newParentFolderId);
            permissionService.requireRole(node, userId, Role.EDITOR);
            permissionService.requireRole(newParent, userId, Role.EDITOR);

            if (node == newParent) {
                throw new InvalidOperationException("Cannot move a folder into itself");
            }
            if (node instanceof Folder && isAncestorOf((Folder) node, newParent)) {
                throw new InvalidOperationException(
                        "Cannot move folder '" + nodeId + "' into its own descendant '" + newParentFolderId + "'");
            }
            if (newParent.hasChildNamed(node.getName())) {
                throw new DuplicateNameException(newParentFolderId, node.getName());
            }

            Folder oldParent = node.getParent();
            if (oldParent != null) {
                oldParent.removeChildByName(node.getName());
            }
            node.setParentInternal(newParent);
            newParent.addChild(node);
            notificationService.publish(new FileChangeEvent(
                    FileChangeEvent.Type.MOVED, nodeId, userId, clock.instant()));
        } finally {
            structuralLock.writeLock().unlock();
        }
    }

    /** @return true if {@code candidateAncestor} is {@code node} or a descendant of {@code node}. */
    private boolean isAncestorOf(Folder node, Folder candidateAncestor) {
        DriveNode current = candidateAncestor;
        while (current != null) {
            if (current == node) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    // -------------------------------------------------------------------------------- listing & search

    public Collection<DriveNode> listChildren(String userId, String folderId) {
        structuralLock.readLock().lock();
        try {
            Folder folder = getFolderOrThrow(folderId);
            permissionService.requireRole(folder, userId, Role.VIEWER);
            return folder.listChildren();
        } finally {
            structuralLock.readLock().unlock();
        }
    }

    public List<DriveNode> search(String userId, SearchService.SearchQuery query) {
        structuralLock.readLock().lock();
        try {
            return searchService.search(userId, query, id -> nodeRepository.findById(id).orElse(null));
        } finally {
            structuralLock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------------------------- sharing

    public void share(String requesterId, String nodeId, String granteeUserId, Role role) {
        DriveNode node = getNodeOrThrow(nodeId);
        permissionService.grant(node, requesterId, granteeUserId, role);
        notificationService.publish(new FileChangeEvent(
                FileChangeEvent.Type.SHARED, nodeId, requesterId, clock.instant()));
    }

    public void revokeShare(String requesterId, String nodeId, String granteeUserId) {
        DriveNode node = getNodeOrThrow(nodeId);
        permissionService.revoke(node, requesterId, granteeUserId);
        notificationService.publish(new FileChangeEvent(
                FileChangeEvent.Type.UNSHARED, nodeId, requesterId, clock.instant()));
    }

    public Role effectivePermission(String nodeId, String userId) {
        return permissionService.effectiveRole(getNodeOrThrow(nodeId), userId);
    }

    // --------------------------------------------------------------------------- trash lifecycle

    public void moveToTrash(String userId, String nodeId) {
        structuralLock.writeLock().lock();
        try {
            DriveNode node = getNodeOrThrow(nodeId);
            permissionService.requireRole(node, userId, Role.EDITOR);
            Folder parent = node.getParent();
            if (parent != null) {
                parent.removeChildByName(node.getName());
            }
            trashService.recordTrashed(node, parent, userId, clock.instant());
            notificationService.publish(new FileChangeEvent(
                    FileChangeEvent.Type.TRASHED, nodeId, userId, clock.instant()));
        } finally {
            structuralLock.writeLock().unlock();
        }
    }

    public void restoreFromTrash(String userId, String nodeId) {
        structuralLock.writeLock().lock();
        try {
            DriveNode node = getNodeOrThrow(nodeId);
            permissionService.requireRole(node, userId, Role.OWNER);
            TrashRecord record = trashService.getTrashRecord(nodeId);

            Folder targetParent;
            try {
                targetParent = record.getOriginalParentId() == null
                        ? null : getFolderOrThrow(record.getOriginalParentId());
            } catch (NodeNotFoundException e) {
                targetParent = null; // original folder itself got purged; fall back below.
            }
            String restoredName = record.getOriginalName();
            if (targetParent != null) {
                // Original name slot may have been reused by something else while trashed.
                int suffix = 1;
                while (targetParent.hasChildNamed(restoredName)) {
                    restoredName = record.getOriginalName() + " (restored " + suffix++ + ")";
                }
                node.renameInternal(restoredName);
                node.setParentInternal(targetParent);
                targetParent.addChild(node);
            }
            trashService.clearTrashRecord(nodeId);
            searchService.reindexRename(node, record.getOriginalName());
            notificationService.publish(new FileChangeEvent(
                    FileChangeEvent.Type.RESTORED, nodeId, userId, clock.instant()));
        } finally {
            structuralLock.writeLock().unlock();
        }
    }

    /** Permanently deletes a single trashed item (and its descendants) right now. Owner-only. */
    public void purgePermanently(String userId, String nodeId) {
        structuralLock.writeLock().lock();
        try {
            DriveNode node = getNodeOrThrow(nodeId);
            permissionService.requireRole(node, userId, Role.OWNER);
            trashService.getTrashRecord(nodeId); // throws if not actually in the trash.
            trashService.purgePermanently(node);
            notificationService.publish(new FileChangeEvent(
                    FileChangeEvent.Type.PURGED, nodeId, userId, clock.instant()));
        } finally {
            structuralLock.writeLock().unlock();
        }
    }

    /** Background-sweeper entry point: purges every trash record past its retention window. */
    public List<String> runTrashRetentionSweep(Instant now) {
        structuralLock.writeLock().lock();
        try {
            return trashService.purgeExpired(now, id -> nodeRepository.findById(id).orElse(null));
        } finally {
            structuralLock.writeLock().unlock();
        }
    }

    public List<TrashRecord> listTrash() {
        return trashService.listTrash();
    }

    // --------------------------------------------------------------------------------- quota

    public long getQuotaUsedBytes(String userId) {
        return quotaManager.getUsedBytes(userId);
    }

    public long getQuotaRemainingBytes(String userId) {
        return quotaManager.getRemainingBytes(userId);
    }
}
