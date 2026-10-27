package com.app.tracker.core.security;

import static com.app.tracker.core.security.WorkspacePermission.COMMENT_MODERATE;
import static com.app.tracker.core.security.WorkspacePermission.COMMENT_WRITE;
import static com.app.tracker.core.security.WorkspacePermission.INTEGRATION_MANAGE;
import static com.app.tracker.core.security.WorkspacePermission.MEMBER_MANAGE;
import static com.app.tracker.core.security.WorkspacePermission.RETRO_ITEM_WRITE;
import static com.app.tracker.core.security.WorkspacePermission.SUBTASK_CREATE_OWN;
import static com.app.tracker.core.security.WorkspacePermission.TASK_DELETE;
import static com.app.tracker.core.security.WorkspacePermission.TASK_EDIT;
import static com.app.tracker.core.security.WorkspacePermission.TASK_SELF_ASSIGN;

import com.app.tracker.workspace.model.WorkspaceRole;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** ADR-0019 uyarinca workspace rol-izin eslemesinin tek tanim noktasi. */
public final class RolePermissions {

  private static final Map<String, Set<WorkspacePermission>> PERMISSIONS =
      Map.of(
          WorkspaceRole.ADMIN, Set.copyOf(EnumSet.allOf(WorkspacePermission.class)),
          WorkspaceRole.MANAGER,
              Set.copyOf(
                  EnumSet.complementOf(
                      EnumSet.of(
                          TASK_DELETE, COMMENT_MODERATE, MEMBER_MANAGE, INTEGRATION_MANAGE))),
          WorkspaceRole.DEVELOPER,
              Set.of(
                  TASK_EDIT, TASK_SELF_ASSIGN, SUBTASK_CREATE_OWN, COMMENT_WRITE, RETRO_ITEM_WRITE),
          WorkspaceRole.VIEWER, Set.of());

  private RolePermissions() {}

  /** Rolun izin kumesi; bilinmeyen veya null rol icin degistirilemez bos kume. */
  public static Set<WorkspacePermission> of(String role) {
    return role == null ? Set.of() : PERMISSIONS.getOrDefault(role, Set.of());
  }

  public static boolean has(String role, WorkspacePermission permission) {
    return of(role).contains(permission);
  }
}
