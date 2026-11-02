package lld.clouddrive.store;

import lld.clouddrive.model.DriveNode;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link NodeRepository}. All mutation of the tree SHAPE (which folder a node lives
 * in) is still coordinated by {@code DriveService}'s structural lock; this repository only
 * provides id -> node lookup, which is safe to serve concurrently via
 * {@link ConcurrentHashMap} regardless of structural locking elsewhere.
 */
public final class InMemoryNodeRepository implements NodeRepository {

    private final ConcurrentHashMap<String, DriveNode> nodes = new ConcurrentHashMap<>();

    @Override
    public void save(DriveNode node) {
        nodes.put(node.getId(), node);
    }

    @Override
    public Optional<DriveNode> findById(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    @Override
    public void deleteById(String nodeId) {
        nodes.remove(nodeId);
    }

    @Override
    public boolean existsById(String nodeId) {
        return nodes.containsKey(nodeId);
    }
}
