package com.app.tracker.task.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.tracker.core.security.WorkspacePermission;
import com.app.tracker.task.model.Task;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class TaskAccessPolicyTest {
  private final WorkspaceMembershipService membershipService =
      mock(WorkspaceMembershipService.class);
  private final TaskAccessPolicy policy = new TaskAccessPolicy(membershipService);
  private final UUID workspaceId = UUID.randomUUID();
  private final UUID actorId = UUID.randomUUID();
  private final UUID otherId = UUID.randomUUID();

  private Task task(UUID assigneeId) {
    Task task = Task.of(UUID.randomUUID(), workspaceId, UUID.randomUUID(), 1, "T");
    task.changeAssignee(assigneeId);
    return task;
  }

  private void actorRole(String role) {
    when(membershipService.findRole(actorId, workspaceId)).thenReturn(Optional.ofNullable(role));
  }

  @Test
  void developerMaySelfAssignReleaseOwnOrReassignSelf() {
    actorRole(WorkspaceRole.DEVELOPER);
    assertDoesNotThrow(() -> policy.checkAssign(task(null), actorId, actorId));
    assertDoesNotThrow(() -> policy.checkAssign(task(actorId), null, actorId));
    assertDoesNotThrow(() -> policy.checkAssign(task(actorId), actorId, actorId));
  }

  @Test
  void developerCannotTouchOthersAssignments() {
    actorRole(WorkspaceRole.DEVELOPER);
    assertThrows(
        AccessDeniedException.class, () -> policy.checkAssign(task(null), otherId, actorId));
    assertThrows(
        AccessDeniedException.class, () -> policy.checkAssign(task(otherId), actorId, actorId));
    assertThrows(
        AccessDeniedException.class, () -> policy.checkAssign(task(otherId), null, actorId));
    assertThrows(
        AccessDeniedException.class, () -> policy.checkAssign(task(actorId), otherId, actorId));
    assertThrows(
        AccessDeniedException.class, () -> policy.checkAssign(task(otherId), otherId, actorId));
    assertThrows(AccessDeniedException.class, () -> policy.checkAssign(task(null), null, actorId));
  }

  @Test
  void plannersMayChangeAnyAssignment() {
    for (String role : new String[] {WorkspaceRole.ADMIN, WorkspaceRole.MANAGER}) {
      reset(membershipService);
      actorRole(role);
      assertDoesNotThrow(() -> policy.checkAssign(task(null), otherId, actorId));
      assertDoesNotThrow(() -> policy.checkAssign(task(otherId), actorId, actorId));
      assertDoesNotThrow(() -> policy.checkAssign(task(otherId), null, actorId));
      assertDoesNotThrow(() -> policy.checkAssign(task(actorId), otherId, actorId));
    }
  }

  @Test
  void roleIsReadFromTheTasksWorkspace() {
    actorRole(WorkspaceRole.MANAGER);
    policy.checkAssign(task(otherId), null, actorId);
    verify(membershipService).findRole(actorId, workspaceId);
  }

  @Test
  void nonMemberOrMissingActorFallsBackToSelfRuleOnly() {
    actorRole(null);
    assertThrows(
        AccessDeniedException.class, () -> policy.checkAssign(task(null), otherId, actorId));
    assertDoesNotThrow(() -> policy.checkAssign(task(null), actorId, actorId));
    assertThrows(AccessDeniedException.class, () -> policy.checkAssign(task(null), otherId, null));
    assertThrows(AccessDeniedException.class, () -> policy.checkAssign(task(null), null, null));
  }

  @Test
  void requireGrantsOnlyWhenRoleHasPermission() {
    actorRole(WorkspaceRole.DEVELOPER);
    assertDoesNotThrow(
        () -> policy.require(actorId, workspaceId, WorkspacePermission.TASK_EDIT, "Toplu STATUS"));
    AccessDeniedException denied =
        assertThrows(
            AccessDeniedException.class,
            () ->
                policy.require(
                    actorId, workspaceId, WorkspacePermission.TASK_PLAN, "Toplu SPRINT islemi"));
    assertTrue(denied.getMessage().contains("SPRINT"));
  }

  @Test
  void requireDeniesWithoutWorkspaceOrActor() {
    assertThrows(
        AccessDeniedException.class,
        () -> policy.require(actorId, null, WorkspacePermission.TASK_EDIT, "x"));
    assertThrows(
        AccessDeniedException.class,
        () -> policy.require(null, workspaceId, WorkspacePermission.TASK_EDIT, "x"));
    verifyNoInteractions(membershipService);
  }
}
