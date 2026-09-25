package com.app.tracker.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.analytics.model.TaskAnalytics;
import com.app.tracker.analytics.repository.TaskAnalyticsRepository;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.flow.dto.AgingWipResponse;
import com.app.tracker.flow.repository.TaskAgingAlertRepository;
import com.app.tracker.flow.service.AgingWipAlertService;
import com.app.tracker.flow.service.AgingWipService;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dalga 2.1 — Aging WIP okuma servisi ({@link AgingWipService}) ve bildirim servisi ({@link
 * AgingWipAlertService}). Esik hesabi {@link AgingWipLevels}'ta paylasildigindan ikisi de AYNI
 * veriyle test edilir: 10 tamamlanmis gorev (cycle_time_seconds = 100..1000, p85 = 865s,
 * percentile_cont lineer interpolasyonu) + acik gorevler.
 */
@SpringBootTest
class AgingWipIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final double P85_SECONDS = 865.0; // 800 + 0.65 * (900 - 800)

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private AgingWipService agingWipService;
  @Autowired private AgingWipAlertService agingWipAlertService;
  @Autowired private TaskAnalyticsRepository taskAnalyticsRepository;
  @Autowired private TaskAgingAlertRepository taskAgingAlertRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;

  private UUID workspaceId;
  private Project project;
  private UUID assigneeId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Aging WS"));
    project = inWorkspace(() -> projectService.createProject("AGE", "Aging Project"));
    assigneeId = registerMember("aging-assignee", WorkspaceRole.DEVELOPER);
  }

  @Test
  void thresholdUnavailableWithFewerThanTenCompletedTasks() {
    for (int i = 0; i < 9; i++) {
      completedSample((i + 1) * 100L);
    }
    createOpenTask(500);

    AgingWipResponse response = inWorkspace(() -> agingWipService.aging(project.getId()));

    assertFalse(response.thresholdAvailable());
    assertTrue(response.items().isEmpty());

    List<Notification> created = inWorkspace(() -> agingWipAlertService.checkProject(project));
    assertTrue(created.isEmpty());
  }

  @Test
  void agingClassifiesOpenTasksByP85AndDoubleP85() {
    for (int i = 1; i <= 10; i++) {
      completedSample(i * 100L);
    }
    Task normal = createOpenTask(800); // < p85 (865)
    Task warning = createOpenTask(900); // >= p85, < 2xp85 (1730)
    Task critical = createOpenTask(2000); // >= 2xp85

    AgingWipResponse response = inWorkspace(() -> agingWipService.aging(project.getId()));

    assertTrue(response.thresholdAvailable());
    assertEquals(P85_SECONDS, response.p85Seconds(), 0.001);
    assertEquals(3, response.items().size());
    // en yasli en basta (age desc).
    assertEquals(critical.getId(), response.items().get(0).taskId());
    assertEquals(2, response.items().get(0).level());
    assertEquals(warning.getId(), response.items().get(1).taskId());
    assertEquals(1, response.items().get(1).level());
    assertEquals(normal.getId(), response.items().get(2).taskId());
    assertEquals(0, response.items().get(2).level());
  }

  @Test
  void alertServiceNotifiesOnceOnEscalationAndClearsWhenBackToNormal() {
    for (int i = 1; i <= 10; i++) {
      completedSample(i * 100L);
    }
    // createOpenTask assign() cagirir; TaskService.assign zaten atanani otomatik izleyici yapar.
    Task task = createOpenTask(900); // seviye 1

    List<Notification> firstRun = inWorkspace(() -> agingWipAlertService.checkProject(project));
    assertEquals(1, firstRun.size());
    assertEquals("TASK_AGING", firstRun.get(0).getType());
    assertEquals(assigneeId, firstRun.get(0).getUserId());
    assertEquals(1, inTenant(() -> taskAgingAlertRepository.currentLevel(task.getId())));

    // Ayni seviyede tekrar calisma: yeni bildirim YOK.
    List<Notification> secondRun = inWorkspace(() -> agingWipAlertService.checkProject(project));
    assertTrue(secondRun.isEmpty());

    // Seviye 2'ye yukselme: yeni bildirim gider.
    updateFirstInProgressAt(task.getId(), 2000);
    List<Notification> thirdRun = inWorkspace(() -> agingWipAlertService.checkProject(project));
    assertEquals(1, thirdRun.size());
    assertEquals(2, inTenant(() -> taskAgingAlertRepository.currentLevel(task.getId())));

    // Gorev Done olunca (task_analytics.doneAt dolar) artik "acik" degildir, kayit temizlenir.
    markDone(task.getId());
    List<Notification> fourthRun = inWorkspace(() -> agingWipAlertService.checkProject(project));
    assertTrue(fourthRun.isEmpty());
    assertEquals(0, inTenant(() -> taskAgingAlertRepository.currentLevel(task.getId())));
  }

  // ---- yardimcilar --------------------------------------------------------------------------

  /** 10+ tamamlanmis ornekten biri: cycle_time_seconds = verilen deger. */
  private void completedSample(long cycleTimeSeconds) {
    Instant done = Instant.now().minusSeconds(1);
    Instant inProgress = done.minusSeconds(cycleTimeSeconds);
    TaskAnalytics analytics = TaskAnalytics.create(UUID.randomUUID(), workspaceId, project.getId());
    analytics.applyStatusChange(TaskStatus.IN_PROGRESS, inProgress);
    analytics.applyStatusChange(TaskStatus.DONE, done);
    inTenant(() -> taskAnalyticsRepository.save(analytics));
  }

  /** Gercek bir Task olusturur (baslik/atanan icin) + acik (Done olmamis) task_analytics satiri. */
  private Task createOpenTask(long ageSeconds) {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "Acik gorev"));
    inWorkspace(() -> taskService.assign(task.getId(), assigneeId, assigneeId));
    Instant firstInProgress = Instant.now().minusSeconds(ageSeconds);
    TaskAnalytics analytics = TaskAnalytics.create(task.getId(), workspaceId, project.getId());
    analytics.applyStatusChange(TaskStatus.IN_PROGRESS, firstInProgress);
    inTenant(() -> taskAnalyticsRepository.save(analytics));
    return task;
  }

  private void updateFirstInProgressAt(UUID taskId, long ageSeconds) {
    inTenant(
        () ->
            entityManager
                .createNativeQuery(
                    "UPDATE task_analytics SET first_in_progress_at = ?1 WHERE task_id = ?2")
                .setParameter(1, Instant.now().minusSeconds(ageSeconds))
                .setParameter(2, taskId)
                .executeUpdate());
  }

  private void markDone(UUID taskId) {
    inTenant(
        () ->
            entityManager
                .createNativeQuery("UPDATE task_analytics SET done_at = NOW() WHERE task_id = ?1")
                .setParameter(1, taskId)
                .executeUpdate());
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

  /**
   * TransactionTemplate AOP'tan gecmez; tenant baglami ({@code SET LOCAL} esdegeri) elle kurulur.
   */
  private <T> T inTenant(Supplier<T> action) {
    return transactionTemplate.execute(
        status -> {
          entityManager
              .createNativeQuery("SELECT set_config('app.current_workspace_id', ?1, true)")
              .setParameter(1, workspaceId.toString())
              .getSingleResult();
          return action.get();
        });
  }

  private void inTenant(Runnable action) {
    inTenant(
        () -> {
          action.run();
          return null;
        });
  }
}
