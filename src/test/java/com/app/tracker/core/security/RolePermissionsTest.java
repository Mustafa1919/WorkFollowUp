package com.app.tracker.core.security;

import static com.app.tracker.core.security.WorkspacePermission.*;
import static org.junit.jupiter.api.Assertions.*;

import com.app.tracker.workspace.model.WorkspaceRole;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RolePermissionsTest {

  @Test
  void eachRoleHasExactlyItsPermissions() {
    assertEquals(EnumSet.allOf(WorkspacePermission.class), RolePermissions.of(WorkspaceRole.ADMIN));
    assertEquals(
        Set.of(
            TASK_CREATE,
            TASK_EDIT,
            TASK_PLAN,
            TASK_SELF_ASSIGN,
            SUBTASK_CREATE_OWN,
            TASK_APPROVE,
            COMMENT_WRITE,
            RETRO_ITEM_WRITE,
            PROJECT_CREATE,
            SPRINT_MANAGE,
            TAG_MANAGE,
            MEETING_MANAGE,
            GOAL_MANAGE,
            AUTOMATION_MANAGE,
            FEEDBACK_READ),
        RolePermissions.of(WorkspaceRole.MANAGER));
    assertEquals(
        Set.of(
            TASK_CREATE,
            TASK_EDIT,
            TASK_PLAN,
            TASK_SELF_ASSIGN,
            SUBTASK_CREATE_OWN,
            COMMENT_WRITE,
            RETRO_ITEM_WRITE),
        RolePermissions.of(WorkspaceRole.DEVELOPER));
    assertEquals(Set.of(), RolePermissions.of(WorkspaceRole.VIEWER));
  }

  @Test
  void unknownRolesHaveNoPermissions() {
    assertEquals(Set.of(), RolePermissions.of("YOK"));
    assertEquals(Set.of(), RolePermissions.of(null));
    assertFalse(RolePermissions.has("YOK", TASK_EDIT));
    assertTrue(RolePermissions.has(WorkspaceRole.DEVELOPER, TASK_EDIT));
    assertFalse(RolePermissions.has(WorkspaceRole.DEVELOPER, TASK_DELETE));
  }

  @Test
  void allReturnedSetsAreImmutable() {
    for (String role :
        new String[] {
          WorkspaceRole.ADMIN,
          WorkspaceRole.MANAGER,
          WorkspaceRole.DEVELOPER,
          WorkspaceRole.VIEWER,
          "YOK",
          null
        }) {
      assertThrows(
          UnsupportedOperationException.class, () -> RolePermissions.of(role).add(TASK_DELETE));
    }
    assertThrows(
        UnsupportedOperationException.class,
        () -> RolePermissions.of(WorkspaceRole.ADMIN).remove(TASK_EDIT));
  }
}
