package com.app.tracker.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.analytics.consumer.CycleTimeConsumer;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.service.SprintService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Dalga 2.2 — Monte Carlo tahmin okuma servisi + Redis Cache-Aside. Gunluk ornekler {@code
 * CycleTimeConsumer.onMessage} ile GERCEK olay akisindan (fake taskId'lerle, task_analytics'in
 * tasks'a FK'si olmadigi icin gecerli — bkz. AnalyticsQueryServiceIntegrationTest ile ayni desen)
 * uretilir; boylece ayni cagri hem projector'i hem forecast onbellek TEMIZLEMEYI (eviction) tek
 * seferde sinar.
 */
@SpringBootTest
class ForecastServiceIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private SprintService sprintService;
  @Autowired private TaskService taskService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private ForecastService forecastService;
  @Autowired private ForecastCacheService forecastCacheService;
  @Autowired private CycleTimeConsumer cycleTimeConsumer;
  @Autowired private Clock clock;

  private UUID workspaceId;
  private Project project;
  private UUID actorId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Forecast WS"));
    project = inWorkspace(() -> projectService.createProject("FCT", "Forecast Project"));
    String email = "forecast-owner-" + UUID.randomUUID() + "@tracker.local";
    actorId = authService.register(email, PASSWORD, "Owner").getId();
    membershipService.addMember(workspaceId, actorId, WorkspaceRole.ADMIN);
  }

  @Test
  void unavailableWithFewerThanTenDailySamples() {
    for (int i = 0; i < 9; i++) {
      completeOne(LocalDate.now(clock).minusDays(i));
    }

    ForecastResponse response = inWorkspace(() -> forecastService.projectBacklog(project.getId()));

    assertFalse(response.available());
  }

  @Test
  void projectBacklogIsCachedUntilEviction() {
    // 10 gun, gunde tam 5 tamamlanma: varyanssiz, sonuc RNG'den bagimsiz deterministik.
    for (int day = 0; day < 10; day++) {
      for (int i = 0; i < 5; i++) {
        completeOne(LocalDate.now(clock).minusDays(day));
      }
    }
    inWorkspace(() -> taskService.createTask(project.getId(), "Acik gorev"));

    ForecastResponse first = inWorkspace(() -> forecastService.projectBacklog(project.getId()));
    assertTrue(first.available());
    assertEquals(1L, first.remainingItems());
    assertTrue(
        inWorkspace(() -> forecastCacheService.getProject(workspaceId, project.getId()))
            .isPresent());

    // Yeni acik gorev eklenir ama onbellek TEMIZLENMEDEN tekrar okunur: eski deger doner.
    Task extra = inWorkspace(() -> taskService.createTask(project.getId(), "Ikinci acik gorev"));
    ForecastResponse cached = inWorkspace(() -> forecastService.projectBacklog(project.getId()));
    assertEquals(first, cached);

    // CycleTimeConsumer bir Done olayi isleyince onbellek TEMIZLENIR (fake taskId, task_analytics
    // tasks'a FK'li degil).
    completeOne(LocalDate.now(clock));
    assertTrue(
        inWorkspace(() -> forecastCacheService.getProject(workspaceId, project.getId())).isEmpty());

    ForecastResponse recomputed =
        inWorkspace(() -> forecastService.projectBacklog(project.getId()));
    assertNotEquals(first.remainingItems(), recomputed.remainingItems());
    assertEquals(2L, recomputed.remainingItems());
  }

  @Test
  void sprintForecastComputesProbabilityAgainstEndDate() {
    // Ornek PENCERENIN TAMAMI (84 gun) gunde sabit 1 tamamlanma ile doldurulur — sadece son 10
    // gunu doldurmak, ortalamayi 84 gunun geri kalanindaki sifirlarla seyreltir (bootstrap TUM
    // pencereden orneklem cekiyor), bu yuzden tam determinizm icin TUM pencere gerekir.
    for (int day = 0; day < ForecastService.SAMPLE_WINDOW_DAYS; day++) {
      completeOne(LocalDate.now(clock).minusDays(day));
    }
    LocalDate today = LocalDate.now(clock);
    Sprint sprint =
        inWorkspace(
            () ->
                sprintService.createSprint(project.getId(), "S1", null, today, today.plusDays(10)));
    for (int i = 0; i < 10; i++) {
      Task task = inWorkspace(() -> taskService.createTask(project.getId(), "Sprint gorevi"));
      inWorkspace(() -> taskService.assignSprint(task.getId(), sprint.getId(), actorId));
    }

    ForecastResponse response = inWorkspace(() -> forecastService.sprint(sprint.getId()));

    assertTrue(response.available());
    assertEquals(10L, response.remainingItems());
    assertEquals(today.plusDays(10), response.targetDate());
    // 10 gunde gunluk sabit 1 tamamlanma ile tam 10 is biter -> olasilik 1.0.
    assertEquals(1.0, response.probabilityByTargetDate());
  }

  /** Fake bir taskId icin dogrudan Kafka payload'i vererek {@code task_analytics} satiri uretir. */
  private void completeOne(LocalDate day) {
    UUID taskId = UUID.randomUUID();
    java.time.Instant at = day.atTime(12, 0).atZone(java.time.ZoneOffset.UTC).toInstant();
    cycleTimeConsumer.onMessage(
        ("{\"eventId\":\"%s\",\"eventType\":\"TASK_STATUS_UPDATED\",\"schemaVersion\":1,"
                + "\"timestamp\":\"%s\",\"aggregateId\":\"%s\",\"workspaceId\":\"%s\","
                + "\"payload\":{\"taskId\":\"%s\",\"projectId\":\"%s\",\"oldStatus\":\"To Do\","
                + "\"newStatus\":\"Done\"}}")
            .formatted(UUID.randomUUID(), at, taskId, workspaceId, taskId, project.getId()));
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }
}
