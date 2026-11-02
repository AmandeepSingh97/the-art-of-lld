package lld.contactmanagement.index;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Trie (prefix tree) implementation of {@link NameIndex}.
 *
 * <p><b>Design choice - eager aggregation.</b> Every node along a token's
 * insertion path stores the set of contact ids whose token passes through
 * that node (not just the terminal node). This means {@link #searchByPrefix}
 * is a single O(|prefix|) walk followed by a direct set read - no subtree
 * traversal/DFS is needed to collect matches. The trade-off is memory: a
 * node near the root (e.g. the node for "s") accumulates ids for every token
 * starting with "s", so total memory is O(sum of token lengths x average
 * fan-in), heavier than a "leaf-only" trie. See README section 10 for the
 * full complexity table versus the leaf-only alternative and versus
 * {@link SortedArrayNameIndex}.
 *
 * <p><b>Concurrency.</b> A single {@link ReentrantReadWriteLock} guards the
 * whole trie. Reads (prefix search) take the read lock and can run
 * concurrently with each other; writes (add/remove) take the write lock and
 * are fully serialized. This is coarse-grained but correct and simple: the
 * trie's node graph is mutated in ways (child creation, pruning) that are
 * awkward to make lock-free safely, and in an address-book-scale workload
 * (thousands to low millions of contacts, read-heavy) a single lock is not a
 * realistic bottleneck. A sharded-lock or lock-free trie is a documented
 * production extension (README section 17), not attempted here to avoid
 * premature complexity.
 */
public final class TrieNameIndex implements NameIndex {

    private static final class Node {
        final Map<Character, Node> children = new HashMap<>();
        final Set<UUID> contactIds = new LinkedHashSet<>();
    }

    private final Node root = new Node();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public void add(String token, UUID contactId) {
        if (token == null || token.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            Node current = root;
            for (int i = 0; i < token.length(); i++) {
                current = current.children.computeIfAbsent(token.charAt(i), c -> new Node());
                current.contactIds.add(contactId);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(String token, UUID contactId) {
        if (token == null || token.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            removeRecursive(root, token, 0, contactId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes {@code contactId} from every node along the token's path
     * (post-order) and prunes any node that becomes empty (no ids remaining
     * at it and no surviving children) to bound memory growth over the
     * lifetime of the index.
     */
    private void removeRecursive(Node node, String token, int index, UUID contactId) {
        if (index == token.length()) {
            return;
        }
        char c = token.charAt(index);
        Node child = node.children.get(c);
        if (child == null) {
            return;
        }
        child.contactIds.remove(contactId);
        removeRecursive(child, token, index + 1, contactId);
        if (child.contactIds.isEmpty() && child.children.isEmpty()) {
            node.children.remove(c);
        }
    }

    @Override
    public Set<UUID> searchByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return Set.of();
        }
        lock.readLock().lock();
        try {
            Node current = root;
            for (int i = 0; i < prefix.length(); i++) {
                current = current.children.get(prefix.charAt(i));
                if (current == null) {
                    return Set.of();
                }
            }
            return Set.copyOf(current.contactIds);
        } finally {
            lock.readLock().unlock();
        }
    }
}
