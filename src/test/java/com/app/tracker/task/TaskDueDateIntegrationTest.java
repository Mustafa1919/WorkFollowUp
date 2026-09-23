package com.app.tracker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V15 — takvim gorunumunun backend'i: {@code due_date} degisikligi tarihceye VE outbox'a yazilir,
 * takvim sorgusu yalniz araliktaki (ve kendi tenant'inin) gorevlerini doner; {@code GET
 * /api/v1/workspaces} header'siz calisir ve yalniz kullanicinin uyeliklerini listeler.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskDueDateIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private MockMvc mockMvc;

  private UUID workspaceId;
  private UUID actorId;
  private Project project;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Due WS"));
    project = inWorkspace(() -> projectService.createProject("DUE", "Due Project"));
    actorId =
        authService.register("due-" + UUID.randomUUID() + "@tracker.local", PASSWORD, "A").getId();
  }

  @Test
  void dueDateChangesAreRecordedInHistoryAndOutbox() {
    LocalDate d1 = LocalDate.of(2099, 9, 10);
    LocalDate d2 = LocalDate.of(2099, 9, 12);
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T1", d1, actorId));
    assertEquals(d1, task.getDueDate());

    inWorkspace(() -> taskService.updateDueDate(task.getId(), d2, actorId));
    inWorkspace(() -> taskService.updateDueDate(task.getId(), d2, actorId)); // no-op
    Task cleared = inWorkspace(() -> taskService.updateDueDate(task.getId(), null, actorId));
    assertNull(cleared.getDueDate());

    List<Object[]> history = dueDateEvents(task.getId());
    assertEquals(3, history.size());
    assertEquals(null, history.get(0)[0]);
    assertEquals("2099-09-10", history.get(0)[1]);
    assertEquals("2099-09-10", history.get(1)[0]);
    assertEquals("2099-09-12", history.get(1)[1]);
    assertEquals("2099-09-12", history.get(2)[0]);
    assertEquals(null, history.get(2)[1]);

    assertEquals(2L, outboxCount(task.getId(), "TASK_DUE_DATE_CHANGED"));
  }

  @Test
  void calendarReturnsOnlyTasksInsideTheRange() {
    inWorkspace(
        () ->
            taskService.createTask(project.getId(), "before", LocalDate.of(2099, 8, 31), actorId));
    Task first =
        inWorkspace(
            () ->
                taskService.createTask(
                    project.getId(), "first", LocalDate.of(2099, 9, 1), actorId));
    Task last =
        inWorkspace(
            () ->
                taskService.createTask(
                    project.getId(), "last", LocalDate.of(2099, 9, 30), actorId));
    inWorkspace(() -> taskService.createTask(project.getId(), "undated"));

    List<Task> tasks =
        inWorkspace(
            () ->
                taskService.listTasksByDueDate(
                    project.getId(), LocalDate.of(2099, 9, 1), LocalDate.of(2099, 9, 30)));

    assertEquals(List.of(first.getId(), last.getId()), tasks.stream().map(Task::getId).toList());
    assertThrows(
        BusinessRuleException.class,
        () ->
            inWorkspace(
                () ->
                    taskService.listTasksByDueDate(
                        project.getId(), LocalDate.of(2099, 9, 30), LocalDate.of(2099, 9, 1))));
  }

  @Test
  void calendarRejectsTooWideRange() throws Exception {
    String token = memberToken(WorkspaceRole.VIEWER);
    int status =
        mockMvc
            .perform(
                get("/api/v1/projects/" + project.getId() + "/tasks/calendar")
                    .param("from", "2026-01-01")
                    .param("to", "2026-06-01")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Workspace-Id", workspaceId.toString()))
            .andReturn()
            .getResponse()
            .getStatus();
    assertEquals(400, status);
  }

  @Test
  void workspaceListShowsOnlyOwnMembershipsWithoutWorkspaceHeader() throws Exception {
    String token = memberToken(WorkspaceRole.DEVELOPER);
    UUID otherWorkspace = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(otherWorkspace, "Foreign"));

    String body =
        mockMvc
            .perform(get("/api/v1/workspaces").header("Authorization", "Bearer " + token))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertTrue(body.contains(workspaceId.toString()), body);
    assertTrue(body.contains("\"role\":\"" + WorkspaceRole.DEVELOPER + "\""), body);
    assertTrue(!body.contains(otherWorkspace.toString()), body);
  }

  private String memberToken(String role) {
    String email = "due-member-" + UUID.randomUUID() + "@tracker.local";
    UUID userId = authService.register(email, PASSWORD, "Member").getId();
    membershipService.addMember(workspaceId, userId, role);
    return authService.login(email, PASSWORD, "127.0.0.1").accessToken();
  }

  private <T> T inWorkspace(java.util.function.Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  /** task_events RLS'e tabi: TransactionTemplate AOP'tan gecmedigi icin context elle kurulur. */
  @SuppressWarnings("unchecked")
  private List<Object[]> dueDateEvents(UUID taskId) {
    return transactionTemplate.execute(
        status -> {
          setTenant();
          return entityManager
              .createNativeQuery(
                  "SELECT old_value ->> 'dueDate', new_value ->> 'dueDate' FROM task_events "
                      + "WHERE task_id = ?1 AND event_type = 'due_date_changed' "
                      + "ORDER BY created_at, id")
              .setParameter(1, taskId)
              .getResultList();
        });
  }

  private long outboxCount(UUID taskId, String eventType) {
    return transactionTemplate.execute(
        status ->
            ((Number)
                    entityManager
                        .createNativeQuery(
                            "SELECT COUNT(*) FROM outbox_events "
                                + "WHERE aggregate_id = ?1 AND event_type = ?2")
                        .setParameter(1, taskId)
                        .setParameter(2, eventType)
                        .getSingleResult())
                .longValue());
  }

  private void setTenant() {
    entityManager
        .createNativeQuery("SELECT set_config('app.current_workspace_id', ?1, true)")
        .setParameter(1, workspaceId.toString())
        .getSingleResult();
  }
}
