package lld.filesystem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure, stateless utility for turning a path string into a normalized list of segments, and for
 * splitting "parent directory path" from "final component name" -- the latter is what every
 * mutating command (mkdir, touch, cp, mv) needs: resolve the parent, then act on one name inside
 * it.
 *
 * <p>No file-system access happens here; this class only knows about strings. All existence /
 * type checks happen in {@link FileSystem}, which is also responsible for locking.</p>
 */
final class PathResolver {

    private PathResolver() {
    }

    static boolean isAbsolute(String path) {
        return path.startsWith("/");
    }

    /**
     * Splits a path into normalized segments, resolving {@code .} (no-op) and {@code ..}
     * (pop last segment) lexically. This is purely textual normalization; it does not verify
     * that the resulting segments actually exist.
     *
     * <p>Note: a leading {@code ..} beyond the number of preceding segments is clamped (extra
     * {@code ..} at the root is a no-op), mirroring common shell/OS behavior rather than
     * throwing, since going "above root" has an obvious, safe interpretation.</p>
     */
    static List<String> normalize(String path) {
        String[] raw = path.split("/");
        List<String> segments = new ArrayList<>();
        for (String part : raw) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.remove(segments.size() - 1);
                }
                continue;
            }
            segments.add(part);
        }
        return segments;
    }

    /** The canonical absolute path string "/a/b/c" (root is "/") for a sequence of segments. */
    static String toPathString(List<String> segments) {
        if (segments.isEmpty()) {
            return "/";
        }
        return "/" + String.join("/", segments);
    }

    /**
     * Splits trailing component name off the path, returning {@code [parentSegments, name]}.
     * Throws if the path has no final component (i.e. it resolves to root itself).
     */
    record ParentAndName(List<String> parentSegments, String name) {
    }

    static ParentAndName splitParentAndName(String path, List<String> cwdSegments) {
        List<String> absoluteSegments = resolveToAbsoluteSegments(path, cwdSegments);
        if (absoluteSegments.isEmpty()) {
            throw new IllegalArgumentException("Path has no final component: " + path);
        }
        String name = absoluteSegments.get(absoluteSegments.size() - 1);
        List<String> parent = absoluteSegments.subList(0, absoluteSegments.size() - 1);
        return new ParentAndName(new ArrayList<>(parent), name);
    }

    /** Resolves {@code path} (absolute or relative to {@code cwdSegments}) into absolute segments. */
    static List<String> resolveToAbsoluteSegments(String path, List<String> cwdSegments) {
        if (isAbsolute(path)) {
            return normalize(path);
        }
        List<String> combined = new ArrayList<>(cwdSegments);
        combined.addAll(Arrays.asList(path.split("/")));
        return normalize(toPathString(combined));
    }
}
