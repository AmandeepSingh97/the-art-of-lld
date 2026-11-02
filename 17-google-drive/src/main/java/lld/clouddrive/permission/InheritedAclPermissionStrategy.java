package lld.clouddrive.permission;

import lld.clouddrive.model.AccessEntry;
import lld.clouddrive.model.DriveNode;
import lld.clouddrive.model.Role;

/**
 * Default permission strategy, modeled on how Drive-style sharing actually behaves:
 * <ol>
 *   <li>The node's owner always has {@link Role#OWNER}, full stop.</li>
 *   <li>If the node itself carries an explicit {@link AccessEntry} for the user, that entry wins
 *       outright -- an explicit grant/restriction at a deeper node always overrides whatever was
 *       granted on an ancestor, it does not merge with it.</li>
 *   <li>Otherwise, the role is inherited by walking up to the parent and asking the same
 *       question, all the way to the root; a node with no parent and no matching entry yields
 *       {@link Role#NONE}.</li>
 * </ol>
 * This makes "share this folder" implicitly share everything under it (new children included,
 * since resolution happens at check-time against the live tree, not by copying ACLs down at
 * share-time), while still allowing a more specific share on one nested file/folder to override
 * the inherited grant for just that subtree.
 */
public final class InheritedAclPermissionStrategy implements PermissionResolutionStrategy {

    @Override
    public Role resolveEffectiveRole(DriveNode node, String userId) {
        DriveNode current = node;
        while (current != null) {
            if (current.getOwnerId().equals(userId)) {
                return Role.OWNER;
            }
            for (AccessEntry entry : current.getAclEntries()) {
                if (entry.getUserId().equals(userId)) {
                    return entry.getRole();
                }
            }
            current = current.getParent();
        }
        return Role.NONE;
    }
}
