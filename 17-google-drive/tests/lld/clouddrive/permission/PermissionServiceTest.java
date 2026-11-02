package lld.clouddrive.permission;

import lld.clouddrive.model.File;
import lld.clouddrive.model.Folder;
import lld.clouddrive.model.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link InheritedAclPermissionStrategy} in isolation from the rest of the
 * service layer: owner precedence, inheritance walking up multiple levels, and explicit
 * override at a deeper node winning over an inherited grant.
 */
class PermissionServiceTest {

    private final PermissionService permissionService = new PermissionService(new InheritedAclPermissionStrategy());

    @Test
    void ownerAlwaysHasOwnerRoleRegardlessOfAcl() {
        Folder root = new Folder("f1", "root", "owner-1", null, Instant.now());
        assertEquals(Role.OWNER, permissionService.effectiveRole(root, "owner-1"));
    }

    @Test
    void userWithNoGrantAnywhereInChainHasNoneRole() {
        Folder root = new Folder("f1", "root", "owner-1", null, Instant.now());
        Folder child = new Folder("f2", "child", "owner-1", root, Instant.now());
        assertEquals(Role.NONE, permissionService.effectiveRole(child, "stranger"));
    }

    @Test
    void grantOnAncestorFolderIsInheritedByDeeplyNestedFile() {
        Folder root = new Folder("f1", "root", "owner-1", null, Instant.now());
        Folder child = new Folder("f2", "child", "owner-1", root, Instant.now());
        File file = new File("file1", "doc.txt", "owner-1", child, Instant.now());

        permissionService.grant(root, "owner-1", "viewer-1", Role.VIEWER);

        assertEquals(Role.VIEWER, permissionService.effectiveRole(file, "viewer-1"));
        assertEquals(Role.VIEWER, permissionService.effectiveRole(child, "viewer-1"));
    }

    @Test
    void explicitGrantOnNodeOverridesInheritedAncestorGrant() {
        Folder root = new Folder("f1", "root", "owner-1", null, Instant.now());
        File file = new File("file1", "doc.txt", "owner-1", root, Instant.now());

        permissionService.grant(root, "owner-1", "user-1", Role.VIEWER);
        permissionService.grant(file, "owner-1", "user-1", Role.EDITOR);

        assertEquals(Role.EDITOR, permissionService.effectiveRole(file, "user-1"));
        assertEquals(Role.VIEWER, permissionService.effectiveRole(root, "user-1"));
    }

    @Test
    void revokeRemovesTheExplicitGrantAndFallsBackToInheritance() {
        Folder root = new Folder("f1", "root", "owner-1", null, Instant.now());
        File file = new File("file1", "doc.txt", "owner-1", root, Instant.now());

        permissionService.grant(root, "owner-1", "user-1", Role.EDITOR);
        permissionService.grant(file, "owner-1", "user-1", Role.VIEWER);
        permissionService.revoke(file, "owner-1", "user-1");

        assertEquals(Role.EDITOR, permissionService.effectiveRole(file, "user-1"),
                "removing the node's own explicit entry should fall back to the inherited ancestor grant");
    }

    @Test
    void nonOwnerCannotGrantAccessToOthers() {
        Folder root = new Folder("f1", "root", "owner-1", null, Instant.now());
        permissionService.grant(root, "owner-1", "editor-1", Role.EDITOR);

        org.junit.jupiter.api.Assertions.assertThrows(
                lld.clouddrive.exception.AccessDeniedException.class,
                () -> permissionService.grant(root, "editor-1", "someone-else", Role.VIEWER));
    }
}
