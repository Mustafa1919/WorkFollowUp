package com.app.tracker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * V16 — gecmis tarih yasagi, tamamlanan gorevin onayi (Kanban'dan kalkar, Tamamlananlar'da
 * listelenir) ve ADMIN'e ozel soft delete.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskApprovalAndDeletionIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private Clock clock;
  @Autowired private MockMvc mockMvc;

  private UUID workspaceId;
  private UUID actorId;
  private Project project;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Appr WS"));
    project = inWorkspace(() -> projectService.createProject("APR", "Approval Project"));
    actorId =
        authService.register("apr-" + UUID.randomUUID() + "@tracker.local", PASSWORD, "A").getId();
  }

  // ---- gecmis tarih ----

  @Test
  void pastDueDateIsRejectedButTodayIsAllowed() {
    LocalDate today = LocalDate.now(clock);
    assertThrows(
        BusinessRuleException.class,
        () ->
            inWorkspace(
                () -> taskService.createTask(project.getId(), "old", today.minusDays(3), actorId)));

    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "today", today, actorId));
    assertEquals(today, task.getDueDate());

    assertThrows(
        BusinessRuleException.class,
        () ->
            inWorkspace(
                () -> taskService.updateDueDate(task.getId(), today.minusDays(1), actorId)));
    // Tarihi kaldirmak (null) serbest.
    assertNull(
        inWorkspace(() -> taskService.updateDueDate(task.getId(), null, actorId)).getDueDate());
  }

  // ---- onay ----

  @Test
  void approvedTaskLeavesBoardListAndAppearsInApprovedList() {
    Task task = doneTask("finished");
    Task open = inWorkspace(() -> taskService.createTask(project.getId(), "open"));

    Task approved = inWorkspace(() -> taskService.approve(task.getId(), actorId));
    assertNotNull(approved.getApprovedAt());
    assertEquals(TaskStatus.DONE, approved.getStatus());

    List<UUID> board = ids(inWorkspace(() -> taskService.listTasks(project.getId(), 50, null)));
    assertTrue(board.contains(open.getId()));
    assertFalse(board.contains(task.getId()));
    List<UUID> approvedIds =
        ids(inWorkspace(() -> taskService.listApprovedTasks(project.getId(), 50, null)));
    assertEquals(List.of(task.getId()), approvedIds);
  }

  @Test
  void onlyDoneTaskCanBeApprovedAndApprovedTaskIsLocked() {
    Task todo = inWorkspace(() -> taskService.createTask(project.getId(), "todo"));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskService.approve(todo.getId(), actorId)));

    Task task = doneTask("locked");
    inWorkspace(() -> taskService.approve(task.getId(), actorId));
    assertThrows(
        BusinessRuleException.class,
        () ->
            inWorkspace(
                () -> taskService.updateStatus(task.getId(), TaskStatus.IN_PROGRESS, actorId)));

    Task revoked = inWorkspace(() -> taskService.revokeApproval(task.getId(), actorId));
    assertNull(revoked.getApprovedAt());
    assertTrue(
        ids(inWorkspace(() -> taskService.listTasks(project.getId(), 50, null)))
            .contains(task.getId()));
  }

  @Test
  void approvedListPaginatesByApprovalTime() {
    Task first = doneTask("first");
    Task second = doneTask("second");
    Task third = doneTask("third");
    for (Task t : List.of(first, second, third)) {
      inWorkspace(() -> taskService.approve(t.getId(), actorId));
    }

    PageResponse<Task> page1 =
        inWorkspace(() -> taskService.listApprovedTasks(project.getId(), 2, null));
    PageResponse<Task> page2 =
        inWorkspace(() -> taskService.listApprovedTasks(project.getId(), 2, page1.nextCursor()));

    assertTrue(page1.hasMore());
    // Ayni milisaniyede onaylananlar id ile siralanir; sira degil, cursor'in eksiksiz ve
    // tekrarsiz gezdigi dogrulanir.
    assertEquals(2, page1.data().size());
    assertFalse(page2.hasMore());
    java.util.Set<UUID> all = new java.util.HashSet<>(ids(page1));
    all.addAll(ids(page2));
    assertEquals(java.util.Set.of(first.getId(), second.getId(), third.getId()), all);
  }

  // ---- silme ----

  @Test
  void deletedTaskDisappearsFromAllReads() {
    LocalDate today = LocalDate.now(clock);
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "gone", today, actorId));

    inWorkspace(
        () -> {
          taskService.deleteTask(task.getId(), actorId);
          return null;
        });

    assertFalse(
        ids(inWorkspace(() -> taskService.listTasks(project.getId(), 50, null)))
            .contains(task.getId()));
    assertTrue(
        inWorkspace(() -> taskService.listTasksByDueDate(project.getId(), today, today)).isEmpty());
    assertThrows(
        ResourceNotFoundException.class,
        () -> inWorkspace(() -> taskService.updateStatus(task.getId(), TaskStatus.DONE, actorId)));
  }

  @Test
  void onlyAdminCanDeleteAndOnlyAdminOrManagerCanApproveOverHttp() throws Exception {
    Task task = doneTask("http");
    String path = "/api/v1/tasks/" + task.getId();

    assertEquals(403, status(delete(path), memberToken(WorkspaceRole.MANAGER)));
    assertEquals(403, status(post(path + "/approval"), memberToken(WorkspaceRole.DEVELOPER)));
    assertEquals(200, status(post(path + "/approval"), memberToken(WorkspaceRole.MANAGER)));
    assertEquals(204, status(delete(path), memberToken(WorkspaceRole.ADMIN)));
    assertEquals(404, status(delete(path), memberToken(WorkspaceRole.ADMIN)));
  }

  private Task doneTask(String title) {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), title));
    return inWorkspace(() -> taskService.updateStatus(task.getId(), TaskStatus.DONE, actorId));
  }

  private int status(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      String token)
      throws Exception {
    return mockMvc
        .perform(
            request
                .header("Authorization", "Bearer " + token)
                .header("X-Workspace-Id", workspaceId.toString()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private String memberToken(String role) {
    String email = "apr-member-" + UUID.randomUUID() + "@tracker.local";
    UUID userId = authService.register(email, PASSWORD, "Member").getId();
    membershipService.addMember(workspaceId, userId, role);
    return authService.login(email, PASSWORD, "127.0.0.1").accessToken();
  }

  private static List<UUID> ids(PageResponse<Task> page) {
    return page.data().stream().map(Task::getId).toList();
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }
}
