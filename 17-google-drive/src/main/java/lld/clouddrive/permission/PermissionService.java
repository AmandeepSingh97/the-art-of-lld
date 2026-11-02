package lld.clouddrive.permission;

import lld.clouddrive.exception.AccessDeniedException;
import lld.clouddrive.model.AccessEntry;
import lld.clouddrive.model.DriveNode;
import lld.clouddrive.model.Role;

/**
 * Facade over permission resolution and enforcement. All ACL mutation (grant/revoke) and all
 * permission checks go through this class so there is exactly one place that decides what
 * "enough access" means.
 */
public final class PermissionService {

    private final PermissionResolutionStrategy strategy;

    public PermissionService(PermissionResolutionStrategy strategy) {
        this.strategy = strategy;
    }

    public Role effectiveRole(DriveNode node, String userId) {
        return strategy.resolveEffectiveRole(node, userId);
    }

    public void requireRole(DriveNode node, String userId, Role required) {
        Role effective = effectiveRole(node, userId);
        if (!effective.isAtLeast(required)) {
            throw new AccessDeniedException(userId, node.getId(), required.name());
        }
    }

    /**
     * Grants {@code role} to {@code granteeUserId} on {@code node}. Only the node's OWNER may
     * grant or revoke access -- an EDITOR may change content but may not change who else can
     * access it, which keeps "who can share this" unambiguous and matches common Drive-style
     * sharing semantics.
     */
    public void grant(DriveNode node, String requesterId, String granteeUserId, Role role) {
        requireRole(node, requesterId, Role.OWNER);
        if (role == Role.OWNER) {
            throw new IllegalArgumentException("Ownership cannot be transferred via grant(); "
                    + "only one OWNER (the creator) is supported per node in this design");
        }
        node.upsertAclEntry(new AccessEntry(granteeUserId, role));
    }

    public void revoke(DriveNode node, String requesterId, String granteeUserId) {
        requireRole(node, requesterId, Role.OWNER);
        node.removeAclEntry(granteeUserId);
    }
}
