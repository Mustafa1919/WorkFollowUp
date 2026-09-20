package com.app.tracker.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.app.tracker.analytics.consumer.VelocityConsumer;
import com.app.tracker.analytics.model.SprintAnalytics;
import com.app.tracker.analytics.repository.SprintAnalyticsRepository;
import com.app.tracker.analytics.service.SprintAnalyticsReconciliationJob;
import com.app.tracker.analytics.service.VelocityProjector;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.service.SprintService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Faz 3 / Dilim 3.3 — Velocity / Spillover worker'i. Ana senaryo GERCEK zincirle ({@code
 * SprintService -> outbox -> Kafka -> VelocityConsumer}) calisir; degismezlik, idempotency ve
 * uzlastirma senaryolari deterministik olmak icin consumer/job'i dogrudan cagirir.
 */
@SpringBootTest
class VelocityIntegrationTest extends AbstractIntegrationTest {

  @Autowired private VelocityConsumer consumer;
  @Autowired private SprintAnalyticsReconciliationJob reconciliationJob;
  @Autowired private SprintAnalyticsRepository sprintAnalyticsRepository;
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
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Velocity WS"));
    project = inWorkspace(() -> projectService.createProject("VEL", "Velocity Project"));
    actorId =
        authService
            .register(
                "velocity-" + UUID.randomUUID() + "@tracker.local",
                "correct-horse-battery",
                "Actor")
            .getId();
  }

  @Test
  void velocityAndSpilloverAreComputedFromTheHistoryAtCompletion() throws Exception {
    Sprint s1 = newSprint("S1");
    Sprint s2 = newSprint("S2");
    Task a = taskIn(s1, 5, TaskStatus.DONE);
    Task b = taskIn(s1, 3, TaskStatus.IN_PROGRESS);
    Task c = taskIn(s1, 8, TaskStatus.DONE);
    Task d = taskIn(s1, null, TaskStatus.DONE); // puansiz: 0 puan, ama gorev sayisinda gorunur
    // e sprint'e girdi ama kapanistan ONCE baska sprint'e tasindi: s1'in parcasi degil.
    Task e = taskIn(s1, 2, TaskStatus.DONE);
    inWorkspace(() -> taskService.assignSprint(e.getId(), s2.getId(), actorId));

    inWorkspace(() -> sprintService.startSprint(s1.getId()));
    Sprint completed = inWorkspace(() -> sprintService.completeSprint(s1.getId()));

    SprintAnalytics row = awaitAnalytics(s1.getId());
    assertEquals("S1", row.getSprintName());
    assertEquals(4, row.getCommittedTasks());
    assertEquals(3, row.getCompletedTasks());
    assertEquals(16L, row.getCommittedPoints()); // 5 + 3 + 8 + 0
    assertEquals(13L, row.getCompletedPoints()); // 5 + 8 + 0
    assertEquals(3L, row.getSpilloverPoints());
    assertEquals(new BigDecimal("0.1875"), row.getSpilloverRate());
    // timestamptz mikrosaniye tutar (yuvarlar), Java Instant nanosaniye: 1 mikrosaniye tolerans.
    assertTrue(
        Duration.between(completed.getCompletedAt(), row.getCompletedAt()).abs().toNanos() <= 1_000,
        "kesit zamani payload'daki completedAt olmali");
    assertEquals(project.getId(), row.getProjectId());
    assertEquals(workspaceId, row.getWorkspaceId());

    // --- Degismezlik: kapanistan SONRAKI her degisiklik gecmis sprint'i etkilemez ---
    inWorkspace(() -> taskService.updateStatus(b.getId(), TaskStatus.DONE, actorId));
    inWorkspace(() -> taskService.updateStoryPoint(a.getId(), 100, actorId));
    inWorkspace(() -> taskService.assignSprint(c.getId(), s2.getId(), actorId)); // spillover
    inWorkspace(() -> taskService.updateStatus(d.getId(), TaskStatus.TO_DO, actorId));

    // Yeni eventId ile ayni sprint'in yeniden hesaplanmasi (or. DLT replay / uzlastirma).
    consumer.onMessage(sprintCompleted(UUID.randomUUID(), s1.getId(), "S1", completed));

    SprintAnalytics again = load(workspaceId, s1.getId()).orElseThrow();
    assertEquals(4, again.getCommittedTasks());
    assertEquals(3, again.getCompletedTasks());
    assertEquals(16L, again.getCommittedPoints());
    assertEquals(13L, again.getCompletedPoints());
    assertEquals(new BigDecimal("0.1875"), again.getSpilloverRate());
    assertTrue(
        !again.getCalculatedAt().isBefore(row.getCalculatedAt()),
        "yeniden hesaplama gercekten calismis olmali (calculated_at ilerler)");
  }

  @Test
  void redeliveredEventIsProcessedExactlyOnce() {
    UUID eventId = UUID.randomUUID();
    UUID sprintId = UUID.randomUUID();
    Instant completedAt = Instant.now();

    consumer.onMessage(envelope(eventId, sprintId, "first", completedAt));
    // Ayni eventId, farkli ad: yinelenen teslimat gercekten ATLANIRSA ad "first" kalir.
    consumer.onMessage(envelope(eventId, sprintId, "second", completedAt));

    assertEquals("first", load(workspaceId, sprintId).orElseThrow().getSprintName());
    assertEquals(1L, processedCount(eventId));
  }

  @Test
  void otherTenantCannotSeeSprintAnalytics() {
    UUID sprintId = UUID.randomUUID();
    consumer.onMessage(envelope(UUID.randomUUID(), sprintId, "S", Instant.now()));

    assertTrue(load(workspaceId, sprintId).isPresent());
    assertTrue(load(UUID.randomUUID(), sprintId).isEmpty(), "baska tenant satiri GOREMEMELI");
    assertTrue(load(null, sprintId).isEmpty(), "context yoksa fail-closed olmali");
  }

  @Test
  void irrelevantEventTypesAreIgnoredAndMalformedOnesAreRejected() {
    UUID sprintId = UUID.randomUUID();
    consumer.onMessage("{}");
    consumer.onMessage(
        envelope(UUID.randomUUID(), sprintId, "S", Instant.now())
            .replace("SPRINT_COMPLETED", "SPRINT_STARTED"));
    assertTrue(load(workspaceId, sprintId).isEmpty());

    assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("not json"));
    assertThrows(
        IllegalArgumentException.class,
        () -> consumer.onMessage("{\"eventType\":\"SPRINT_COMPLETED\",\"payload\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            consumer.onMessage(
                envelope(UUID.randomUUID(), sprintId, "S", Instant.now())
                    .replaceFirst("\"completedAt\":\"[^\"]+\"", "\"completedAt\":\"garbage\"")));
  }

  @Test
  void nightlyReconciliationFillsOnlyMissingSprints() {
    Sprint sprint = newSprint("Lost");
    taskIn(sprint, 5, TaskStatus.DONE);
    taskIn(sprint, 2, TaskStatus.IN_PROGRESS);
    // SPRINT_COMPLETED olayi HIC uretilmemis/kaybolmus bir tamamlanmis sprint: dogrudan SQL ile
    // kapatilir (servis outbox'a olay yazardi ve worker satiri kendisi olustururdu).
    markCompletedWithoutEvent(sprint.getId());
    assertTrue(load(workspaceId, sprint.getId()).isEmpty());

    reconciliationJob.reconcile();

    SprintAnalytics row = load(workspaceId, sprint.getId()).orElseThrow();
    assertEquals("Lost", row.getSprintName());
    assertEquals(2, row.getCommittedTasks());
    assertEquals(1, row.getCompletedTasks());
    assertEquals(7L, row.getCommittedPoints());
    assertEquals(5L, row.getCompletedPoints());
    assertEquals(new BigDecimal("0.2857"), row.getSpilloverRate());

    // Ikinci kosu mevcut satiri YENIDEN hesaplamaz.
    Instant firstCalculatedAt = row.getCalculatedAt();
    reconciliationJob.reconcile();
    assertEquals(
        firstCalculatedAt, load(workspaceId, sprint.getId()).orElseThrow().getCalculatedAt());
  }

  @Test
  void tasksWithoutAnyStatusOrPointHistoryDefaultToToDoAndZeroPoints() {
    Sprint sprint = newSprint("Bare");
    taskIn(sprint, null, null);
    inWorkspace(() -> sprintService.startSprint(sprint.getId()));
    Sprint completed = inWorkspace(() -> sprintService.completeSprint(sprint.getId()));

    consumer.onMessage(sprintCompleted(UUID.randomUUID(), sprint.getId(), "Bare", completed));

    SprintAnalytics row = load(workspaceId, sprint.getId()).orElseThrow();
    assertEquals(1, row.getCommittedTasks());
    assertEquals(0, row.getCompletedTasks());
    assertEquals(0L, row.getCommittedPoints());
    assertNull(row.getSpilloverRate());
  }

  // ---- yardimcilar -------------------------------------------------------------------------

  private Sprint newSprint(String name) {
    return inWorkspace(
        () ->
            sprintService.createSprint(
                project.getId(), name, null, LocalDate.now(), LocalDate.now().plusDays(14)));
  }

  /** {@code status == null}: durum hic degistirilmez (baslangic "To Do"). */
  private Task taskIn(Sprint sprint, Integer points, String status) {
    Task task =
        inWorkspace(() -> taskService.createTask(project.getId(), "T-" + UUID.randomUUID()));
    if (points != null) {
      inWorkspace(() -> taskService.updateStoryPoint(task.getId(), points, actorId));
    }
    inWorkspace(() -> taskService.assignSprint(task.getId(), sprint.getId(), actorId));
    if (status != null) {
      inWorkspace(() -> taskService.updateStatus(task.getId(), status, actorId));
    }
    return task;
  }

  private SprintAnalytics awaitAnalytics(UUID sprintId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      Optional<SprintAnalytics> row = load(workspaceId, sprintId);
      if (row.isPresent()) {
        return row.get();
      }
      Thread.sleep(250);
    }
    return fail("30 saniye icinde outbox -> Kafka -> worker zinciri sprint_analytics'i doldurmadi");
  }

  private String sprintCompleted(UUID eventId, UUID sprintId, String name, Sprint completed) {
    return envelope(eventId, sprintId, name, completed.getCompletedAt());
  }

  private String envelope(UUID eventId, UUID sprintId, String name, Instant completedAt) {
    return ("{\"eventId\":\"%s\",\"eventType\":\"SPRINT_COMPLETED\",\"schemaVersion\":1,"
            + "\"timestamp\":\"%s\",\"aggregateId\":\"%s\",\"workspaceId\":\"%s\","
            + "\"payload\":{\"sprintId\":\"%s\",\"projectId\":\"%s\",\"name\":\"%s\","
            + "\"completedAt\":\"%s\"}}")
        .formatted(
            eventId,
            Instant.now(),
            sprintId,
            workspaceId,
            sprintId,
            project.getId(),
            name,
            completedAt);
  }

  private void markCompletedWithoutEvent(UUID sprintId) {
    inTenant(
        workspaceId,
        () ->
            entityManager
                .createNativeQuery(
                    "UPDATE sprints SET status = 'completed', started_at = NOW(), "
                        + "completed_at = NOW() WHERE id = ?1")
                .setParameter(1, sprintId)
                .executeUpdate());
  }

  private Optional<SprintAnalytics> load(UUID tenant, UUID sprintId) {
    return inTenant(tenant, () -> sprintAnalyticsRepository.findById(sprintId));
  }

  private long processedCount(UUID eventId) {
    return ((Number)
            transactionTemplate.execute(
                status ->
                    entityManager
                        .createNativeQuery(
                            "SELECT COUNT(*) FROM processed_events "
                                + "WHERE consumer = ?1 AND event_id = ?2")
                        .setParameter(1, VelocityProjector.CONSUMER)
                        .setParameter(2, eventId)
                        .getSingleResult()))
        .longValue();
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  /**
   * TransactionTemplate AOP'tan gecmedigi icin tenant context'i (SET LOCAL esdegeri) elle kurulur.
   */
  private <T> T inTenant(UUID tenant, Supplier<T> action) {
    return transactionTemplate.execute(
        status -> {
          if (tenant != null) {
            entityManager
                .createNativeQuery("SELECT set_config('app.current_workspace_id', ?1, true)")
                .setParameter(1, tenant.toString())
                .getSingleResult();
          }
          return action.get();
        });
  }
}
