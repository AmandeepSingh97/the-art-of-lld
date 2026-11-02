package lld.clouddrive.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composite node: a directory that can hold both {@link File} leaves and other {@code Folder}
 * composites, forming the classic filesystem tree.
 *
 * <p>{@link #children} is a plain {@link LinkedHashMap} (insertion-ordered, for predictable
 * listing) rather than a concurrent collection. This is deliberate: all structural mutation
 * (add/remove child) and all iteration happen exclusively while {@code DriveService} holds its
 * tree-structural read/write lock (see {@link DriveNode} javadoc), so adding a second layer of
 * per-map concurrency control here would be redundant and would not, by itself, make
 * multi-step operations like "move" (remove from one folder, add to another) atomic anyway.
 */
public final class Folder extends DriveNode {

    private final Map<String, DriveNode> children = new LinkedHashMap<>();

    public Folder(String id, String name, String ownerId, Folder parent, Instant createdAt) {
        super(id, name, ownerId, parent, createdAt);
    }

    @Override
    public boolean isFolder() {
        return true;
    }

    public boolean hasChildNamed(String name) {
        return children.containsKey(name);
    }

    public DriveNode getChild(String name) {
        return children.get(name);
    }

    public void addChild(DriveNode node) {
        children.put(node.getName(), node);
    }

    public DriveNode removeChildByName(String name) {
        return children.remove(name);
    }

    public Collection<DriveNode> listChildren() {
        return List.copyOf(children.values());
    }

    /** All descendant nodes (files and folders), depth-first, not including this folder itself. */
    public List<DriveNode> listDescendants() {
        List<DriveNode> result = new ArrayList<>();
        collectDescendants(this, result);
        return result;
    }

    private static void collectDescendants(Folder folder, List<DriveNode> out) {
        for (DriveNode child : folder.listChildren()) {
            out.add(child);
            if (child instanceof Folder) {
                collectDescendants((Folder) child, out);
            }
        }
    }
}
