package lld.clouddrive.store;

import lld.clouddrive.model.DriveNode;

import java.util.Optional;

/**
 * Repository abstraction over node persistence (Repository pattern). Hides whether nodes live in
 * a plain in-memory map (this LLD) or a database table keyed by node id (production evolution,
 * see README section 12) behind a small, storage-agnostic interface that {@code DriveService}
 * depends on.
 */
public interface NodeRepository {

    void save(DriveNode node);

    Optional<DriveNode> findById(String nodeId);

    void deleteById(String nodeId);

    boolean existsById(String nodeId);
}
