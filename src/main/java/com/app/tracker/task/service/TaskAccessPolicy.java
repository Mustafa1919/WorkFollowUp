package com.app.tracker.task.service;

import com.app.tracker.core.security.RolePermissions;
import com.app.tracker.core.security.WorkspacePermission;
import com.app.tracker.task.model.Task;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * ADR-0019 — kosullu izinlerin ince kurali (iki katman: anotasyon kaba izni, bu sinif servis
 * katmaninda ayrintiyi denetler). Izin, cagiranin ({@code actorId}) ilgili workspace'teki rolunden
 * {@link RolePermissions} ile turetilir; rol her cagrida {@code workspace_users} uzerinden taze
 * okunur. Servis transaction'i icinden cagrilmalidir.
 */
@Component
public class TaskAccessPolicy {

  private final WorkspaceMembershipService membershipService;

  public TaskAccessPolicy(WorkspaceMembershipService membershipService) {
    this.membershipService = membershipService;
  }

  /**
   * Atama degisikligi icin ince kural (ADR-0019 madde 3). Cagiranda TASK_PLAN varsa her atama
   * serbest; yoksa yalniz: (a) atanmamis goreve kendini atama, (b) kendi atamasini birakma (null),
   * (c) zaten kendine atanmis goreve kendini yeniden atama (no-op). Aksi halde {@link
   * AccessDeniedException}.
   */
  public void checkAssign(Task task, UUID newAssigneeId, UUID actorId) {
    if (has(actorId, task.getWorkspaceId(), WorkspacePermission.TASK_PLAN)) {
      return;
    }
    UUID currentAssigneeId = task.getAssigneeId();
    boolean actorIsCurrent = actorId != null && actorId.equals(currentAssigneeId);
    boolean selfAssignUnassigned =
        currentAssigneeId == null && actorId != null && actorId.equals(newAssigneeId);
    boolean releaseOwn = newAssigneeId == null && actorIsCurrent;
    boolean reassignSelf = actorIsCurrent && Objects.equals(actorId, newAssigneeId);
    if (!(selfAssignUnassigned || releaseOwn || reassignSelf)) {
      throw new AccessDeniedException(
          "Gorev atamasini degistirmek icin planlama yetkisi gerekli; yalniz kendinizi atanmamis"
              + " goreve atayabilir ya da kendi atamanizi birakabilirsiniz.");
    }
  }

  /**
   * Cagiranin verilen workspace'te izni yoksa {@link AccessDeniedException}; {@code reason} hata
   * mesajina eklenir.
   */
  public void require(
      UUID actorId, UUID workspaceId, WorkspacePermission permission, String reason) {
    if (!has(actorId, workspaceId, permission)) {
      throw new AccessDeniedException(reason + " icin " + permission + " yetkisi gerekli.");
    }
  }

  private boolean has(UUID actorId, UUID workspaceId, WorkspacePermission permission) {
    if (actorId == null || workspaceId == null) {
      return false;
    }
    return membershipService
        .findRole(actorId, workspaceId)
        .map(role -> RolePermissions.has(role, permission))
        .orElse(false);
  }
}
