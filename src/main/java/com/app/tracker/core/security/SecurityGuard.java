package com.app.tracker.core.security;

import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.2 — {@code @PreAuthorize("@securityGuard...")} SpEL
 * ifadelerinden cagrilir. Workspace rolu JWT'ye GOMULMEZ (payload sadece userId/roller/jti tasir,
 * bkz. Bolum 1.1) — kullanici birden fazla workspace'e farkli rollerle uye olabildigi icin rol her
 * istekte {@code workspace_users} uzerinden taze okunur.
 */
@Component("securityGuard")
public class SecurityGuard {

  private final WorkspaceMembershipService membershipService;

  public SecurityGuard(WorkspaceMembershipService membershipService) {
    this.membershipService = membershipService;
  }

  /** Aktif TenantContext workspace'inde cagiranin rolu bu izni veriyor mu. */
  public boolean can(String permission) {
    return canAny(permission);
  }

  /** Verilen izinlerden en az biri; uyelik rolu yalniz bir kez okunur. */
  public boolean canAny(String... permissions) {
    WorkspacePermission[] requested =
        Arrays.stream(permissions)
            .map(WorkspacePermission::valueOf)
            .toArray(WorkspacePermission[]::new);
    UUID workspaceId = TenantContext.getWorkspaceId();
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (workspaceId == null
        || authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof UUID userId)) {
      return false;
    }
    return membershipService
        .findRole(userId, workspaceId)
        .map(
            role ->
                Arrays.stream(requested)
                    .anyMatch(permission -> RolePermissions.has(role, permission)))
        .orElse(false);
  }
}
