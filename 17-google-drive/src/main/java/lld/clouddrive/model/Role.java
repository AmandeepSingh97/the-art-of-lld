package lld.clouddrive.model;

/**
 * Permission levels a user can hold on a {@link DriveNode}, ordered from weakest to strongest.
 * Ordinal order is used deliberately for {@link #isAtLeast(Role)} comparisons -- declaration
 * order IS the privilege order, so reordering these constants would silently change semantics.
 * (A production system would use an explicit integer "rank" field instead of relying on ordinal
 * for exactly this reason; it is called out as a trade-off in the README.)
 */
public enum Role {
    NONE,
    VIEWER,
    EDITOR,
    OWNER;

    /** @return true if this role grants at least as much privilege as {@code required}. */
    public boolean isAtLeast(Role required) {
        return this.ordinal() >= required.ordinal();
    }
}
