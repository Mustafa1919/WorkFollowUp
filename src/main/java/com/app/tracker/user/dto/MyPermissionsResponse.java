package com.app.tracker.user.dto;

import com.app.tracker.core.security.RolePermissions;
import com.app.tracker.core.security.WorkspacePermission;
import java.util.Comparator;
import java.util.List;

/** {@code permissions} enum tanim sirasina gore siralidir (deterministik). */
public record MyPermissionsResponse(String role, List<WorkspacePermission> permissions) {

  /** TaskResponse ile AYNI EI_EXPOSE_REP savunmasi: List.copyOf. */
  public MyPermissionsResponse {
    permissions = List.copyOf(permissions);
  }

  public static MyPermissionsResponse of(String role) {
    return new MyPermissionsResponse(
        role, RolePermissions.of(role).stream().sorted(Comparator.naturalOrder()).toList());
  }
}
