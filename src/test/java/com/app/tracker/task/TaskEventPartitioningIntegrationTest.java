package com.app.tracker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskEventRepository;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Faz 3 / Dilim 3.1 — PHASE_3_DETAILED_DESIGN.md Bolum 1.0. Partitioned tabloya gecis sonrasi uc
 * sey dogrulanir: (1) partition'lar otomatik ve idempotent aciliyor, satirlar dogru aya yonleniyor
 * ve sorgular yalnizca ilgili partition'i tariyor (pruning), (2) RLS parent uzerinden calismaya
 * DEVAM ediyor, (3) partition'lara dogrudan erisim app_runtime icin KAPALI (parent'taki RLS
 * politikasi partition'a dogrudan sorguda uygulanmaz — bkz. V10 tuzak 2).
 *
 * <p>Test ayi olarak 2040 kullanilir: gercek "simdiki" partition'larla cakismaz, testler tekrar
 * calistirilabilir.
 */
@SpringBootTest
class TaskEventPartitioningIntegrationTest extends AbstractIntegrationTest {

  private static final OffsetDateTime FEB_2040 =
      OffsetDateTime.of(2040, 2, 15, 12, 0, 0, 0, ZoneOffset.UTC);

  @Autowired private WorkspaceService workspaceService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private TaskEventRepository taskEventRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;

  private UUID workspaceId;
  private UUID actorId;
  private Task task;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Part WS"));
    Project project =
        tenantExecutor.runAs(workspaceId, () -> projectService.createProject("PRT", "Partition"));
    task = tenantExecutor.runAs(workspaceId, () -> taskService.createTask(project.getId(), "T1"));
    actorId =
        authService
            .register(
                "part-" + UUID.randomUUID() + "@tracker.local", "correct-horse-battery", "Actor")
            .getId();
  }

  @Test
  void tableIsPartitionedAndCurrentAndFutureMonthsAreReady() {
    Object partitioned =
        transactionTemplate.execute(
            status ->
                entityManager
                    .createNativeQuery(
                        "SELECT COUNT(*) FROM pg_partitioned_table WHERE partrelid = 'task_events'::regclass")
                    .getSingleResult());
    assertEquals(1L, ((Number) partitioned).longValue());

    // Uygulama acilisindaki job (ApplicationReadyEvent) + V10, simdiki aydan +3 ay'i hazir tutar.
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    for (int i = 0; i <= 3; i++) {
      String name = partitionName(today.plusMonths(i));
      assertNotNull(regclass(name), name + " partition'i hazir olmaliydi");
    }
  }

  @Test
  void ensurePartitionsCreatesMissingMonthsAndIsIdempotent() {
    LocalDate from = LocalDate.of(2041, 1, 1);
    LocalDate to = LocalDate.of(2041, 3, 20);

    assertEquals(3, taskEventRepository.ensurePartitions(from, to));
    assertEquals(0, taskEventRepository.ensurePartitions(from, to), "ikinci cagri no-op olmali");
    assertNotNull(regclass("task_events_2041_03"));
  }

  @Test
  void rowsRouteToTheirMonthAndQueriesPruneOtherPartitions() {
    taskEventRepository.ensurePartitions(LocalDate.of(2040, 1, 1), LocalDate.of(2040, 3, 1));
    UUID eventId = insertEvent(FEB_2040);

    String partition =
        inTenant(
            workspaceId,
            () ->
                (String)
                    entityManager
                        .createNativeQuery(
                            "SELECT tableoid::regclass::text FROM task_events WHERE id = ?1")
                        .setParameter(1, eventId)
                        .getSingleResult());
    assertEquals("task_events_2040_02", partition);

    String plan = explain("2040-02-01 00:00:00+00", "2040-03-01 00:00:00+00");
    assertTrue(plan.contains("task_events_2040_02"), plan);
    assertFalse(plan.contains("task_events_2040_01"), "ocak partition'i taranmamali:\n" + plan);
    assertFalse(plan.contains("task_events_2040_03"), "mart partition'i taranmamali:\n" + plan);
  }

  @Test
  void rlsStillIsolatesTenantsThroughTheParentTable() {
    taskEventRepository.ensurePartitions(LocalDate.of(2040, 1, 1), LocalDate.of(2040, 3, 1));
    UUID eventId = insertEvent(FEB_2040);

    UUID otherWorkspace = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(otherWorkspace, "Other WS"));

    assertEquals(1L, countVisible(workspaceId, eventId));
    assertEquals(0L, countVisible(otherWorkspace, eventId), "baska tenant olayi GOREMEMELI");
    assertEquals(0L, countVisible(null, eventId), "context yoksa fail-closed olmali");
  }

  @Test
  void partitionsCannotBeQueriedDirectlyByTheRuntimeRole() {
    taskEventRepository.ensurePartitions(LocalDate.of(2040, 1, 1), LocalDate.of(2040, 3, 1));

    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () ->
                transactionTemplate.execute(
                    status ->
                        entityManager
                            .createNativeQuery("SELECT COUNT(*) FROM task_events_2040_02")
                            .getSingleResult()));
    assertTrue(
        NestedExceptionUtils.getMostSpecificCause(ex).getMessage().contains("permission denied"),
        "partition'a dogrudan erisim RLS'i atlardi; yetki geri alinmis olmali");
  }

  private UUID insertEvent(OffsetDateTime createdAt) {
    UUID id = UUID.randomUUID();
    inTenant(
        workspaceId,
        () ->
            entityManager
                .createNativeQuery(
                    "INSERT INTO task_events (id, task_id, actor_id, event_type, created_at) "
                        + "VALUES (?1, ?2, ?3, 'partition_test', ?4)")
                .setParameter(1, id)
                .setParameter(2, task.getId())
                .setParameter(3, actorId)
                .setParameter(4, createdAt)
                .executeUpdate());
    return id;
  }

  private long countVisible(UUID tenant, UUID eventId) {
    return ((Number)
            inTenant(
                tenant,
                () ->
                    entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM task_events WHERE id = ?1")
                        .setParameter(1, eventId)
                        .getSingleResult()))
        .longValue();
  }

  @SuppressWarnings("unchecked")
  private String explain(String fromInclusive, String toExclusive) {
    List<String> lines =
        inTenant(
            workspaceId,
            () ->
                entityManager
                    .createNativeQuery(
                        "EXPLAIN SELECT * FROM task_events WHERE created_at >= '"
                            + fromInclusive
                            + "' AND created_at < '"
                            + toExclusive
                            + "'")
                    .getResultList());
    return String.join("\n", lines);
  }

  private String regclass(String name) {
    return transactionTemplate.execute(
        status ->
            (String)
                entityManager
                    .createNativeQuery("SELECT to_regclass(?1)::text")
                    .setParameter(1, "public." + name)
                    .getSingleResult());
  }

  /**
   * TransactionTemplate AOP'tan gecmedigi icin tenant context'i (SET LOCAL esdegeri) elle kurulur.
   */
  private <T> T inTenant(UUID tenant, java.util.function.Supplier<T> action) {
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

  private static String partitionName(LocalDate month) {
    return "task_events_%04d_%02d".formatted(month.getYear(), month.getMonthValue());
  }
}
