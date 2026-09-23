package com.app.tracker.sprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.service.SprintService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Faz 3 / Dilim 3.0 — Analitik Worker'in (Dilim 3.2/3.3) ihtiyac duydugu ham veri gercekten
 * uretiliyor mu: sprint yasam dongusu + {@code SPRINT_COMPLETED} olayi + {@code task_events}
 * tarihcesindeki {@code sprint_changed} / {@code story_point_changed} kayitlari. Velocity'nin
 * degismezligi (PHASE_3 Bolum 1.1) bu tarihcenin eksiksiz olmasina baglidir.
 */
@SpringBootTest
class SprintLifecycleIntegrationTest extends AbstractIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private ProjectService projectService;
  @Autowired private SprintService sprintService;
  @Autowired private TaskService taskService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;

  private UUID workspaceId;
  private UUID actorId;
  private Project project;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Sprint WS"));
    project = inWorkspace(() -> projectService.createProject("SPR", "Sprint Project"));
    actorId =
        authService
            .register(
                "sprint-" + UUID.randomUUID() + "@tracker.local", "correct-horse-battery", "Actor")
            .getId();
  }

  @Test
  void onlyOneActiveSprintPerProject() {
    Sprint first = newSprint("S1");
    Sprint second = newSprint("S2");

    inWorkspace(() -> sprintService.startSprint(first.getId()));

    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> sprintService.startSprint(second.getId())));

    inWorkspace(() -> sprintService.completeSprint(first.getId()));
    Sprint started = inWorkspace(() -> sprintService.startSprint(second.getId()));
    assertEquals("active", started.getStatus());
  }

  @Test
  void invalidStateTransitionsAreRejected() {
    Sprint planned = newSprint("S1");

    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> sprintService.completeSprint(planned.getId())));

    inWorkspace(() -> sprintService.startSprint(planned.getId()));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> sprintService.startSprint(planned.getId())));
  }

  @Test
  void completingSprintEmitsEventCarryingTheCutoffTimestamp() {
    Sprint sprint = newSprint("S1");
    inWorkspace(() -> sprintService.startSprint(sprint.getId()));

    Sprint completed = inWorkspace(() -> sprintService.completeSprint(sprint.getId()));

    List<?> rows =
        transactionTemplate.execute(
            status ->
                entityManager
                    .createNativeQuery(
                        "SELECT topic, payload ->> 'completedAt' FROM outbox_events "
                            + "WHERE aggregate_id = ?1 AND event_type = 'SPRINT_COMPLETED'")
                    .setParameter(1, sprint.getId())
                    .getResultList());
    assertEquals(1, rows.size());
    Object[] row = (Object[]) rows.get(0);
    assertEquals("sprint.events", row[0]);
    assertEquals(completed.getCompletedAt().toString(), row[1]);
  }

  @Test
  void sprintAssignmentIsRecordedInTaskEventHistory() {
    Sprint s1 = newSprint("S1");
    Sprint s2 = newSprint("S2");
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T1"));

    inWorkspace(() -> taskService.assignSprint(task.getId(), s1.getId(), actorId));
    inWorkspace(() -> taskService.assignSprint(task.getId(), s2.getId(), actorId));
    inWorkspace(() -> taskService.assignSprint(task.getId(), s2.getId(), actorId)); // no-op
    inWorkspace(() -> taskService.assignSprint(task.getId(), null, actorId));

    List<Object[]> events = sprintEvents(task.getId());
    assertEquals(3, events.size(), "ayni sprint'e tekrar atama olay uretmemeli");
    assertNull(events.get(0)[0]);
    assertEquals(s1.getId().toString(), events.get(0)[1]);
    assertEquals(s1.getId().toString(), events.get(1)[0]);
    assertEquals(s2.getId().toString(), events.get(1)[1]);
    assertEquals(s2.getId().toString(), events.get(2)[0]);
    assertNull(events.get(2)[1]);
  }

  @Test
  void taskCannotJoinCompletedSprintButCanLeaveIt() {
    Sprint s1 = newSprint("S1");
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T1"));
    inWorkspace(() -> taskService.assignSprint(task.getId(), s1.getId(), actorId));
    inWorkspace(() -> sprintService.startSprint(s1.getId()));
    inWorkspace(() -> sprintService.completeSprint(s1.getId()));

    Task other = inWorkspace(() -> taskService.createTask(project.getId(), "T2"));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskService.assignSprint(other.getId(), s1.getId(), actorId)));

    // Spillover: tamamlanmis sprint'ten cikarmak serbest.
    Task moved = inWorkspace(() -> taskService.assignSprint(task.getId(), null, actorId));
    assertNull(moved.getSprintId());
  }

  @Test
  void taskCannotJoinSprintOfAnotherProject() {
    Project otherProject = inWorkspace(() -> projectService.createProject("OTH", "Other"));
    Sprint foreign =
        inWorkspace(
            () ->
                sprintService.createSprint(
                    otherProject.getId(),
                    "Foreign",
                    null,
                    LocalDate.of(2099, 1, 1),
                    LocalDate.of(2099, 1, 15)));
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T1"));

    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskService.assignSprint(task.getId(), foreign.getId(), actorId)));
  }

  @Test
  void storyPointChangesAreStoredAndRecorded() {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T1"));

    inWorkspace(() -> taskService.updateStoryPoint(task.getId(), 5, actorId));
    inWorkspace(() -> taskService.updateStoryPoint(task.getId(), 5, actorId)); // no-op
    inWorkspace(() -> taskService.updateStoryPoint(task.getId(), 8, actorId));
    assertEquals("8", storedStoryPoint(task.getId()));

    inWorkspace(() -> taskService.updateStoryPoint(task.getId(), null, actorId));
    assertNull(storedStoryPoint(task.getId()));

    List<Object[]> events = storyPointEvents(task.getId());
    assertEquals(3, events.size(), "ayni degerle tekrar atama olay uretmemeli");
    assertNull(events.get(0)[0]);
    assertEquals("5", events.get(0)[1]);
    assertEquals("5", events.get(1)[0]);
    assertEquals("8", events.get(1)[1]);
    assertEquals("8", events.get(2)[0]);
    assertNull(events.get(2)[1]);
  }

  @Test
  void sprintWithPastStartDateIsRejected() {
    // 2 gun: test JVM saat dilimi ile is saat dilimi (Istanbul) gece yarisi farki yaniltmasin.
    LocalDate yesterday = LocalDate.now().minusDays(2);

    assertThrows(
        BusinessRuleException.class,
        () ->
            inWorkspace(
                () ->
                    sprintService.createSprint(
                        project.getId(), "Past Sprint", null, yesterday, yesterday.plusDays(14))));
  }

  @Test
  void otherWorkspaceCannotSeeOrStartSprint() {
    Sprint sprint = newSprint("S1");
    UUID otherWorkspace = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(otherWorkspace, "Other WS"));

    assertThrows(
        ResourceNotFoundException.class,
        () ->
            tenantExecutor.runAs(otherWorkspace, () -> sprintService.startSprint(sprint.getId())));
  }

  private Sprint newSprint(String name) {
    return inWorkspace(
        () ->
            sprintService.createSprint(
                project.getId(), name, null, LocalDate.of(2099, 1, 1), LocalDate.of(2099, 1, 15)));
  }

  private <T> T inWorkspace(java.util.function.Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  private List<Object[]> sprintEvents(UUID taskId) {
    return events(taskId, "sprint_changed", "sprintId");
  }

  private List<Object[]> storyPointEvents(UUID taskId) {
    return events(taskId, "story_point_changed", "storyPoint");
  }

  /** task_events RLS'e tabi: TransactionTemplate AOP'tan gecmedigi icin context elle kurulur. */
  @SuppressWarnings("unchecked")
  private List<Object[]> events(UUID taskId, String eventType, String field) {
    return transactionTemplate.execute(
        status -> {
          setTenant();
          return entityManager
              .createNativeQuery(
                  "SELECT old_value ->> ?3, new_value ->> ?3 FROM task_events "
                      + "WHERE task_id = ?1 AND event_type = ?2 ORDER BY created_at, id")
              .setParameter(1, taskId)
              .setParameter(2, eventType)
              .setParameter(3, field)
              .getResultList();
        });
  }

  private String storedStoryPoint(UUID taskId) {
    return transactionTemplate.execute(
        status -> {
          setTenant();
          return (String)
              entityManager
                  .createNativeQuery(
                      "SELECT custom_fields ->> 'story_point' FROM tasks WHERE id = ?1")
                  .setParameter(1, taskId)
                  .getSingleResult();
        });
  }

  private void setTenant() {
    entityManager
        .createNativeQuery("SELECT set_config('app.current_workspace_id', ?1, true)")
        .setParameter(1, workspaceId.toString())
        .getSingleResult();
  }
}
