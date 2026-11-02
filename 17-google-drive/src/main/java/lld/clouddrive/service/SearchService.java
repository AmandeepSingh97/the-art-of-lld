package lld.clouddrive.service;

import lld.clouddrive.model.DriveNode;
import lld.clouddrive.model.File;
import lld.clouddrive.model.Role;
import lld.clouddrive.permission.PermissionService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Maintains a simple in-memory inverted index from name tokens to node ids, so search does not
 * have to walk the whole tree on every query. Tokens are kept intentionally simple
 * (lower-cased alphanumeric runs) -- this is explicitly scoped as "good enough for an LLD
 * interview", with the README noting that a production system would hand this off to a real
 * search engine (Elasticsearch/Lucene) for ranking, fuzzy match, and metadata faceting at scale.
 */
public final class SearchService {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");

    private final Map<String, Set<String>> tokenIndex = new ConcurrentHashMap<>();
    private final PermissionService permissionService;

    public SearchService(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    public void indexNode(DriveNode node) {
        for (String token : tokenize(node.getName())) {
            tokenIndex.computeIfAbsent(token, t -> ConcurrentHashMap.newKeySet()).add(node.getId());
        }
    }

    public void reindexRename(DriveNode node, String oldName) {
        removeFromIndex(node.getId(), oldName);
        indexNode(node);
    }

    public void removeFromIndex(String nodeId, String name) {
        for (String token : tokenize(name)) {
            Set<String> ids = tokenIndex.get(token);
            if (ids != null) {
                ids.remove(nodeId);
            }
        }
    }

    private static List<String> tokenize(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String[] parts = TOKEN_SPLIT.split(lower);
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    /**
     * Value object describing an optional metadata filter layered on top of the name-token match.
     */
    public static final class SearchQuery {
        public final String nameContains;
        public final Boolean foldersOnly;
        public final Long minSizeBytes;
        public final Long maxSizeBytes;

        public SearchQuery(String nameContains, Boolean foldersOnly, Long minSizeBytes, Long maxSizeBytes) {
            this.nameContains = nameContains;
            this.foldersOnly = foldersOnly;
            this.minSizeBytes = minSizeBytes;
            this.maxSizeBytes = maxSizeBytes;
        }
    }

    /**
     * Searches by name token(s) plus optional metadata filters, returning only nodes on which
     * {@code requestingUserId} has at least {@link Role#VIEWER} (own or inherited). Results are
     * sorted by name for deterministic output.
     */
    public List<DriveNode> search(String requestingUserId, SearchQuery query,
                                   java.util.function.Function<String, DriveNode> nodeResolver) {
        Set<String> candidateIds;
        if (query.nameContains == null || query.nameContains.isBlank()) {
            candidateIds = Set.copyOf(allIndexedIds());
        } else {
            candidateIds = Set.of();
            for (String token : tokenize(query.nameContains)) {
                Set<String> matches = tokenIndex.getOrDefault(token, Set.of());
                candidateIds = candidateIds.isEmpty() ? matches : intersect(candidateIds, matches);
            }
        }

        List<DriveNode> results = new ArrayList<>();
        for (String id : candidateIds) {
            DriveNode node = nodeResolver.apply(id);
            if (node == null) {
                continue; // stale index entry for a purged node; tolerated, cleaned up lazily.
            }
            if (!permissionService.effectiveRole(node, requestingUserId).isAtLeast(Role.VIEWER)) {
                continue;
            }
            if (query.foldersOnly != null && query.foldersOnly != node.isFolder()) {
                continue;
            }
            if (!node.isFolder() && (query.minSizeBytes != null || query.maxSizeBytes != null)) {
                File file = (File) node;
                long size = file.getCurrentVersion() == null ? 0 : file.getCurrentVersion().getSizeBytes();
                if (query.minSizeBytes != null && size < query.minSizeBytes) continue;
                if (query.maxSizeBytes != null && size > query.maxSizeBytes) continue;
            }
            results.add(node);
        }
        results.sort(Comparator.comparing(DriveNode::getName));
        return results;
    }

    private Set<String> allIndexedIds() {
        Set<String> all = ConcurrentHashMap.newKeySet();
        for (Set<String> ids : tokenIndex.values()) {
            all.addAll(ids);
        }
        return all;
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        for (String id : a) {
            if (b.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }
}
