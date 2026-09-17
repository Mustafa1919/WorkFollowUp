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

  public boolean hasWorkspaceRole(UUID workspaceId, String... roles) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    UUID userId = (UUID) authentication.getPrincipal();
    return membershipService
        .findRole(userId, workspaceId)
        .map(role -> Arrays.asList(roles).contains(role))
        .orElse(false);
  }

  /** WorkspaceContextFilter zaten dogruladigi icin, aktif TenantContext'e karsi rol kontrolu. */
  public boolean hasCurrentWorkspaceRole(String... roles) {
    UUID workspaceId = TenantContext.getWorkspaceId();
    if (workspaceId == null) {
      return false;
    }
    return hasWorkspaceRole(workspaceId, roles);
  }
}
