package lld.clouddrive.service;

import lld.clouddrive.exception.InvalidOperationException;
import lld.clouddrive.model.DriveNode;
import lld.clouddrive.model.File;
import lld.clouddrive.model.FileVersion;
import lld.clouddrive.model.Folder;
import lld.clouddrive.model.TrashRecord;
import lld.clouddrive.quota.QuotaManager;
import lld.clouddrive.store.BlobStore;
import lld.clouddrive.store.NodeRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Soft-delete (trash/recycle-bin) lifecycle: trash -> restore, or trash -> retention expiry ->
 * permanent purge. Permanent purge is the single place that actually frees resources: it drops
 * one blob reference per file version (dedup-aware) and releases the owner's quota for every
 * byte that version reserved.
 *
 * <p>Trashed items still count against the owner's quota (matching real Drive behavior: you
 * must empty the trash to actually reclaim space) -- this is a deliberate, documented choice
 * (README section 16) rather than an oversight.
 *
 * <p>This service does not itself lock the tree structure: {@code DriveService} already holds
 * its structural write lock for the duration of trash/restore/purge calls, so detaching/
 * reattaching a node from its parent is already serialized with respect to every other
 * structural operation (move, rename, create, delete) before this class is ever invoked.
 */
public final class TrashService {

    private final Map<String, TrashRecord> trashByNodeId = new ConcurrentHashMap<>();
    private final BlobStore blobStore;
    private final QuotaManager quotaManager;
    private final NodeRepository nodeRepository;
    private final SearchService searchService;
    private final Duration retentionPeriod;

    public TrashService(BlobStore blobStore, QuotaManager quotaManager, NodeRepository nodeRepository,
                         SearchService searchService, Duration retentionPeriod) {
        this.blobStore = blobStore;
        this.quotaManager = quotaManager;
        this.nodeRepository = nodeRepository;
        this.searchService = searchService;
        this.retentionPeriod = retentionPeriod;
    }

    public void recordTrashed(DriveNode node, Folder originalParent, String actorUserId, Instant now) {
        TrashRecord record = new TrashRecord(
                node.getId(),
                originalParent == null ? null : originalParent.getId(),
                node.getName(),
                now,
                now.plus(retentionPeriod),
                actorUserId);
        trashByNodeId.put(node.getId(), record);
    }

    public TrashRecord getTrashRecord(String nodeId) {
        TrashRecord record = trashByNodeId.get(nodeId);
        if (record == null) {
            throw new InvalidOperationException("Node '" + nodeId + "' is not in the trash");
        }
        return record;
    }

    public void clearTrashRecord(String nodeId) {
        trashByNodeId.remove(nodeId);
    }

    public List<TrashRecord> listTrash() {
        return List.copyOf(trashByNodeId.values());
    }

    /**
     * Permanently removes {@code node} and, if it is a folder, every descendant: releases each
     * file version's blob reference and the owner's reserved quota, then deletes every node from
     * the repository and search index. Idempotent per node (already-purged descendants are
     * skipped) so a retry after a partial crash cannot double-release quota or over-decrement a
     * blob's ref count.
     */
    public void purgePermanently(DriveNode node) {
        List<DriveNode> toDelete = new ArrayList<>();
        toDelete.add(node);
        if (node instanceof Folder) {
            toDelete.addAll(((Folder) node).listDescendants());
        }

        for (DriveNode n : toDelete) {
            if (!nodeRepository.existsById(n.getId())) {
                continue; // already purged in an earlier (possibly retried) call.
            }
            if (!n.isFolder()) {
                File file = (File) n;
                for (FileVersion version : file.listVersions()) {
                    blobStore.release(version.getContentHash());
                    quotaManager.release(n.getOwnerId(), version.getSizeBytes());
                }
            }
            nodeRepository.deleteById(n.getId());
            searchService.removeFromIndex(n.getId(), n.getName());
            trashByNodeId.remove(n.getId());
        }
    }

    /** Purges every trash record whose retention window has elapsed as of {@code now}. */
    public List<String> purgeExpired(Instant now, java.util.function.Function<String, DriveNode> nodeResolver) {
        List<String> purgedIds = new ArrayList<>();
        for (TrashRecord record : List.copyOf(trashByNodeId.values())) {
            if (record.isExpired(now)) {
                DriveNode node = nodeResolver.apply(record.getNodeId());
                if (node != null) {
                    purgePermanently(node);
                    purgedIds.add(record.getNodeId());
                } else {
                    trashByNodeId.remove(record.getNodeId());
                }
            }
        }
        return purgedIds;
    }
}
