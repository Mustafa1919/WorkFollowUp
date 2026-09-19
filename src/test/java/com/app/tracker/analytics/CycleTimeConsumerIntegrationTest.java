package com.app.tracker.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.app.tracker.analytics.consumer.CycleTimeConsumer;
import com.app.tracker.analytics.model.TaskAnalytics;
import com.app.tracker.analytics.repository.TaskAnalyticsRepository;
import com.app.tracker.analytics.service.CycleTimeProjector;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Faz 3 / Dilim 3.2 — Cycle Time worker'i. Consumer metodu dogrudan cagrilarak (Kafka zamanlamasina
 * bagli olmadan) deterministik olarak, ardindan gercek {@code TaskService -> outbox -> Kafka ->
 * worker} zinciri uctan uca sinanir.
 */
@SpringBootTest
class CycleTimeConsumerIntegrationTest extends AbstractIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-01T09:00:00Z");

  @Autowired private CycleTimeConsumer consumer;
  @Autowired private TaskAnalyticsRepository taskAnalyticsRepository;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;

  private UUID workspaceId;
  private UUID projectId;
  private UUID taskId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    projectId = UUID.randomUUID();
    taskId = UUID.randomUUID();
  }

  @Test
  void statusEventsProduceCycleTime() {
    consumer.onMessage(envelope(UUID.randomUUID(), TaskStatus.IN_PROGRESS, T0));
    consumer.onMessage(envelope(UUID.randomUUID(), TaskStatus.DONE, T0.plusSeconds(7200)));

    TaskAnalytics a = load(workspaceId, taskId).orElseThrow();
    assertEquals(T0, a.getFirstInProgressAt());
    assertEquals(7200L, a.getCycleTimeSeconds());
    assertEquals(projectId, a.getProjectId());
  }

  @Test
  void redeliveredEventIsProcessedExactlyOnce() {
    UUID eventId = UUID.randomUUID();
    consumer.onMessage(envelope(eventId, TaskStatus.IN_PROGRESS, T0));

    // Ayni eventId, bu kez "Done" iceriyor: yinelenen teslimat gercekten ATLANIRSA Done etkisi
    // gorulmez. (Atlanmasaydi durum Done olurdu.)
    consumer.onMessage(envelope(eventId, TaskStatus.DONE, T0.plusSeconds(60)));

    TaskAnalytics a = load(workspaceId, taskId).orElseThrow();
    assertNull(a.getDoneAt(), "yinelenen olay ikinci kez islenmemeli");
    assertEquals(1L, processedCount(eventId));
  }

  @Test
  void staleEventWithNewIdIsIgnored() {
    consumer.onMessage(envelope(UUID.randomUUID(), TaskStatus.IN_PROGRESS, T0));
    consumer.onMessage(envelope(UUID.randomUUID(), TaskStatus.DONE, T0.plusSeconds(1000)));

    // Farkli eventId (idempotency'yi gecer) ama Done'dan ESKI tarihli: DLT replay benzeri durum.
    consumer.onMessage(envelope(UUID.randomUUID(), TaskStatus.TO_DO, T0.plusSeconds(500)));

    TaskAnalytics a = load(workspaceId, taskId).orElseThrow();
    assertEquals(1000L, a.getCycleTimeSeconds());
    assertEquals(T0.plusSeconds(1000), a.getDoneAt());
  }

  @Test
  void otherTenantCannotSeeAnalytics() {
    consumer.onMessage(envelope(UUID.randomUUID(), TaskStatus.IN_PROGRESS, T0));

    assertTrue(load(workspaceId, taskId).isPresent());
    assertTrue(load(UUID.randomUUID(), taskId).isEmpty(), "baska tenant satiri GOREMEMELI");
    assertTrue(load(null, taskId).isEmpty(), "context yoksa fail-closed olmali");
  }

  @Test
  void irrelevantEventTypesAreIgnoredAndMalformedOnesAreRejected() {
    consumer.onMessage("{}");
    consumer.onMessage("{\"eventType\":\"TASK_CREATED\",\"eventId\":\"x\"}");
    assertTrue(load(workspaceId, taskId).isEmpty());

    // Yapisal bozukluk yeniden denemekle duzelmez: IllegalArgumentException = dogrudan DLT.
    assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("not json"));
    assertThrows(
        IllegalArgumentException.class,
        () -> consumer.onMessage("{\"eventType\":\"TASK_STATUS_UPDATED\",\"payload\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            consumer.onMessage(
                envelope(UUID.randomUUID(), TaskStatus.DONE, T0).replace("2026-09-01", "garbage")));
  }

  @Test
  void realStatusChangesFlowThroughOutboxAndKafkaIntoAnalytics() throws Exception {
    UUID ws = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(ws, "E2E WS"));
    Project project = tenantExecutor.runAs(ws, () -> projectService.createProject("E2E", "E2E"));
    Task task = tenantExecutor.runAs(ws, () -> taskService.createTask(project.getId(), "T"));
    UUID actor =
        authService
            .register("e2e-" + UUID.randomUUID() + "@tracker.local", "correct-horse-battery", "A")
            .getId();

    tenantExecutor.runAs(ws, () -> taskService.updateStatus(task.getId(), "In Progress", actor));
    tenantExecutor.runAs(ws, () -> taskService.updateStatus(task.getId(), "Done", actor));

    long deadline = System.currentTimeMillis() + 30_000;
    TaskAnalytics a = null;
    while (System.currentTimeMillis() < deadline) {
      a = load(ws, task.getId()).orElse(null);
      if (a != null && a.getDoneAt() != null) {
        break;
      }
      Thread.sleep(250);
    }
    if (a == null || a.getDoneAt() == null) {
      fail("30 saniye icinde outbox -> Kafka -> worker zinciri task_analytics'i doldurmadi");
    }
    assertNotNull(a.getFirstInProgressAt());
    assertNotNull(a.getCycleTimeSeconds());
    assertEquals(ws, a.getWorkspaceId());
  }

  private String envelope(UUID eventId, String newStatus, Instant at) {
    return ("{\"eventId\":\"%s\",\"eventType\":\"TASK_STATUS_UPDATED\",\"schemaVersion\":1,"
            + "\"timestamp\":\"%s\",\"aggregateId\":\"%s\",\"workspaceId\":\"%s\","
            + "\"payload\":{\"taskId\":\"%s\",\"projectId\":\"%s\",\"oldStatus\":\"x\","
            + "\"newStatus\":\"%s\"}}")
        .formatted(eventId, at, taskId, workspaceId, taskId, projectId, newStatus);
  }

  private Optional<TaskAnalytics> load(UUID tenant, UUID id) {
    return inTenant(tenant, () -> taskAnalyticsRepository.findById(id));
  }

  private long processedCount(UUID eventId) {
    return ((Number)
            transactionTemplate.execute(
                status ->
                    entityManager
                        .createNativeQuery(
                            "SELECT COUNT(*) FROM processed_events "
                                + "WHERE consumer = ?1 AND event_id = ?2")
                        .setParameter(1, CycleTimeProjector.CONSUMER)
                        .setParameter(2, eventId)
                        .getSingleResult()))
        .longValue();
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
