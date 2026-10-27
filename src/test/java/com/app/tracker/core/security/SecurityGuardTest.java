package com.app.tracker.core.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityGuardTest {
  private final WorkspaceMembershipService membershipService =
      mock(WorkspaceMembershipService.class);
  private final SecurityGuard guard = new SecurityGuard(membershipService);
  private final UUID userId = UUID.randomUUID();
  private final UUID workspaceId = UUID.randomUUID();

  @BeforeEach
  void setContext() {
    TenantContext.setWorkspaceId(workspaceId);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
  }

  @AfterEach
  void clearContext() {
    TenantContext.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void permissionsUseFreshMembershipOnEachCall() {
    when(membershipService.findRole(userId, workspaceId))
        .thenReturn(Optional.of(WorkspaceRole.DEVELOPER), Optional.of(WorkspaceRole.VIEWER));
    assertTrue(guard.can("TASK_EDIT"));
    assertFalse(guard.can("TASK_EDIT"));
    verify(membershipService, times(2)).findRole(userId, workspaceId);
  }

  @Test
  void canAnyReadsRoleOnceAndAcceptsAnyMatchingPermission() {
    when(membershipService.findRole(userId, workspaceId))
        .thenReturn(Optional.of(WorkspaceRole.DEVELOPER));
    assertTrue(guard.canAny("TASK_DELETE", "TASK_SELF_ASSIGN"));
    verify(membershipService).findRole(userId, workspaceId);
  }

  @Test
  void absentPermissionOrMembershipReturnsFalse() {
    when(membershipService.findRole(userId, workspaceId))
        .thenReturn(Optional.of(WorkspaceRole.DEVELOPER), Optional.empty());
    assertFalse(guard.can("TASK_DELETE"));
    assertFalse(guard.can("TASK_EDIT"));
  }

  @Test
  void missingTenantSkipsMembershipLookup() {
    TenantContext.clear();
    assertFalse(guard.can("TASK_EDIT"));
    verifyNoInteractions(membershipService);
  }

  @Test
  void missingOrUnauthenticatedIdentitySkipsMembershipLookup() {
    SecurityContextHolder.clearContext();
    assertFalse(guard.can("TASK_EDIT"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null));
    assertFalse(guard.can("TASK_EDIT"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    assertFalse(guard.can("TASK_EDIT"));
    verifyNoInteractions(membershipService);
  }

  @Test
  void invalidPermissionFailsEvenAfterAValidPermissionOrWithoutContext() {
    assertThrows(IllegalArgumentException.class, () -> guard.can("YOK"));
    assertThrows(IllegalArgumentException.class, () -> guard.canAny("TASK_EDIT", "YOK"));
    TenantContext.clear();
    SecurityContextHolder.clearContext();
    assertThrows(IllegalArgumentException.class, () -> guard.can("YOK"));
    verifyNoInteractions(membershipService);
  }

  @Test
  void emptyPermissionListCannotGrantAccess() {
    when(membershipService.findRole(userId, workspaceId))
        .thenReturn(Optional.of(WorkspaceRole.ADMIN));
    assertFalse(guard.canAny());
  }
}
