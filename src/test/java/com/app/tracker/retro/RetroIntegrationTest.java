package com.app.tracker.retro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.analytics.repository.TaskAnalyticsRepository;
import com.app.tracker.analytics.service.VelocityProjector;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.dependency.service.TaskDependencyService;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.retro.dto.RetroItemResponse;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.service.SprintService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

/**
 * Dalga 2.4 (ADR-0015) — veriye dayali retro + retro panosu. Analitik Kafka round-trip yerine
 * {@code VelocityProjector.reconcileMissing()} DOGRUDAN cagrilarak deterministik hesaplanir
 * (VelocityIntegrationTest'in "uzlastirma" senaryosuyla AYNI desen).
 */
@SpringBootTest
class RetroIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private SprintService sprintService;
  @Autowired private TaskService taskService;
  @Autowired private TaskDependencyService taskDependencyService;
  @Autowired private VelocityProjector velocityProjector;
  @Autowired private TaskAnalyticsRepository taskAnalyticsRepository;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private RetroService retroService;
  @Autowired private RetroItemService retroItemService;

  private UUID workspaceId;
  private Project project;
  private UUID actorId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Retro WS"));
    project = inWorkspace(() -> projectService.createProject("RET", "Retro Project"));
    actorId = registerMember("retro-owner", WorkspaceRole.ADMIN);
  }

  @Test
  void retroRequiresACompletedSprint() {
    Sprint planned = newSprint("S0");

    assertThrows(
        BusinessRuleException.class, () -> inWorkspace(() -> retroService.build(planned.getId())));
  }

  @Test
  void retroComputesScopeChangeSpilloverOutliersAndBlocker() throws InterruptedException {
    Sprint sprint = newSprint("S1");
    Task keptAndDone = taskIn(sprint, TaskStatus.DONE);
    awaitCycleTime(keptAndDone.getId());
    Task removedMidSprint = taskIn(sprint, null);

    inWorkspace(() -> sprintService.startSprint(sprint.getId()));

    // Scope creep: sprint basladiktan SONRA eklenen gorev.
    Task addedMidSprint = taskIn(sprint, TaskStatus.DONE);
    // Sprint'ten cikarilan: baslangicta vardi, kapanistan once backlog'a alindi.
    inWorkspace(() -> taskService.assignSprint(removedMidSprint.getId(), null, actorId));
    // Spillover: kapanista hala Done degil.
    Task spillover = taskIn(sprint, TaskStatus.IN_PROGRESS);
    // Acik blocker: spillover gorevi hala Done olmayan bir blocker tarafindan bloklaniyor.
    Task blocker = inWorkspace(() -> taskService.createTask(project.getId(), "Blocker"));
    inWorkspace(() -> taskDependencyService.link(spillover.getId(), blocker.getId(), actorId));

    Sprint completed = inWorkspace(() -> sprintService.completeSprint(sprint.getId()));
    inWorkspace(velocityProjector::reconcileMissing);

    RetroResponse retro = inWorkspace(() -> retroService.build(completed.getId()));

    assertTrue(retro.planAvailable());
    assertEquals(2, retro.committedAtStartTasks()); // keptAndDone + removedMidSprint
    assertEquals(3, retro.committedTasks()); // keptAndDone + addedMidSprint + spillover
    assertTaskIds(retro.addedTasks(), addedMidSprint.getId());
    assertTaskIds(retro.removedTasks(), removedMidSprint.getId());
    assertTaskIds(retro.spilloverTasks(), spillover.getId());
    assertEquals(1, retro.cycleTimeOutliers().size());
    assertEquals(keptAndDone.getId(), retro.cycleTimeOutliers().get(0).task().taskId());
    assertTrue(retro.longestOpenBlocker() != null);
    assertEquals(spillover.getId(), retro.longestOpenBlocker().blockedTask().taskId());
    assertEquals(blocker.getId(), retro.longestOpenBlocker().blockingTask().taskId());
    // Yetersiz gecmis throughput (bu test workspace'i yeni): tahmin YAPILMAZ.
    assertNull(retro.forecastProbabilityAtStart());
  }

  @Test
  void retroBoardSupportsCreateListDeleteAndConvertToTask() {
    Sprint sprint = newSprint("S2");
    inWorkspace(() -> sprintService.startSprint(sprint.getId()));
    Sprint completed = inWorkspace(() -> sprintService.completeSprint(sprint.getId()));
    inWorkspace(velocityProjector::reconcileMissing);

    RetroItemResponse wentWell =
        inWorkspace(
            () ->
                retroItemService.create(
                    completed.getId(), RetroItemKind.WENT_WELL, "Iyi gitti", actorId));
    RetroItemResponse action =
        inWorkspace(
            () ->
                retroItemService.create(
                    completed.getId(), RetroItemKind.ACTION, "CI'yi hizlandir", actorId));

    List<RetroItemResponse> items = inWorkspace(() -> retroItemService.list(completed.getId()));
    assertEquals(2, items.size());
    assertEquals("Bilinmeyen kullanici".equals(items.get(0).authorName()), false);

    UUID otherUserId = registerMember("retro-other", WorkspaceRole.DEVELOPER);
    assertThrows(
        AccessDeniedException.class,
        () -> inWorkspaceVoid(() -> retroItemService.delete(wentWell.id(), otherUserId)));

    Task created = inWorkspace(() -> retroItemService.convertToTask(action.id(), actorId));
    assertEquals("CI'yi hizlandir", created.getTitle());
    // Tamamlanmis bir sprint'e yeni gorev EKLENEMEZ (V9 kurali) — yeni gorev backlog'a duser.
    assertNull(created.getSprintId());
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> retroItemService.convertToTask(action.id(), actorId)));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> retroItemService.convertToTask(wentWell.id(), actorId)));

    inWorkspaceVoid(() -> retroItemService.delete(wentWell.id(), actorId));
    assertEquals(1, inWorkspace(() -> retroItemService.list(completed.getId())).size());
  }

  // ---- yardimcilar --------------------------------------------------------------------------

  private Sprint newSprint(String name) {
    return inWorkspace(
        () ->
            sprintService.createSprint(
                project.getId(), name, null, LocalDate.of(2099, 1, 1), LocalDate.of(2099, 1, 15)));
  }

  /** {@code status == null}: durum hic degistirilmez (baslangic "To Do"), sprint'e atanir. */
  private Task taskIn(Sprint sprint, String status) {
    Task task =
        inWorkspace(() -> taskService.createTask(project.getId(), "T-" + UUID.randomUUID()));
    inWorkspace(() -> taskService.assignSprint(task.getId(), sprint.getId(), actorId));
    if (TaskStatus.IN_PROGRESS.equals(status) || TaskStatus.DONE.equals(status)) {
      inWorkspace(() -> taskService.updateStatus(task.getId(), TaskStatus.IN_PROGRESS, actorId));
    }
    if (TaskStatus.DONE.equals(status)) {
      inWorkspace(() -> taskService.updateStatus(task.getId(), TaskStatus.DONE, actorId));
    }
    return task;
  }

  /** Kafka round-trip'in {@code task_analytics.cycle_time_seconds}'i yazmasini bekler (async). */
  private void awaitCycleTime(UUID taskId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      boolean ready =
          inWorkspace(
              () ->
                  taskAnalyticsRepository
                      .findById(taskId)
                      .map(a -> a.getCycleTimeSeconds() != null)
                      .orElse(false));
      if (ready) {
        return;
      }
      Thread.sleep(200);
    }
  }

  private static void assertTaskIds(List<RetroTaskRef> refs, UUID expectedTaskId) {
    assertFalse(refs.isEmpty());
    assertTrue(refs.stream().anyMatch(r -> r.taskId().equals(expectedTaskId)));
  }

  private UUID registerMember(String prefix, String role) {
    String email = prefix + "-" + UUID.randomUUID() + "@tracker.local";
    UUID userId = authService.register(email, PASSWORD, "User").getId();
    membershipService.addMember(workspaceId, userId, role);
    return userId;
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  private void inWorkspaceVoid(Runnable action) {
    inWorkspace(
        () -> {
          action.run();
          return null;
        });
  }
}
