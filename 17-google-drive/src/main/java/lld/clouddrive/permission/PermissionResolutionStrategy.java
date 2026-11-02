package lld.clouddrive.permission;

import lld.clouddrive.model.DriveNode;
import lld.clouddrive.model.Role;

/**
 * Strategy for computing a user's effective {@link Role} on a node. Extracted behind an
 * interface so the inheritance semantics can be swapped (e.g. a flat "no inheritance, every node
 * needs its own explicit grant" policy for a more locked-down product tier) without touching
 * {@code PermissionService} or any caller that depends on it.
 */
public interface PermissionResolutionStrategy {

    Role resolveEffectiveRole(DriveNode node, String userId);
}
