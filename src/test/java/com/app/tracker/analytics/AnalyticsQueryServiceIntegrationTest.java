package com.app.tracker.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.analytics.consumer.CycleTimeConsumer;
import com.app.tracker.analytics.dto.CycleTimeResponse;
import com.app.tracker.analytics.dto.ThroughputResponse;
import com.app.tracker.analytics.dto.ThroughputResponse.WeeklyThroughput;
import com.app.tracker.analytics.dto.VelocityResponse;
import com.app.tracker.analytics.model.SprintAnalytics;
import com.app.tracker.analytics.model.SprintSnapshot;
import com.app.tracker.analytics.repository.SprintAnalyticsRepository;
import com.app.tracker.analytics.service.AnalyticsQueryService;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Faz 3 / Dilim 3.3 — analitik okuma servisi (Velocity listesi, Weekly Throughput, Cycle Time
 * dagilimi). Veri, gercek projector/consumer kodlariyla uretilir; servis {@code @ReadReplica}
 * yolundan (bkz. ReadReplicaRoutingIntegrationTest) cagrilir.
 */
@SpringBootTest
class AnalyticsQueryServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private AnalyticsQueryService service;
  @Autowired private CycleTimeConsumer cycleTimeConsumer;
  @Autowired private SprintAnalyticsRepository sprintAnalyticsRepository;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private ProjectService projectService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;

  private UUID workspaceId;
  private Project project;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Query WS"));
    project = inWorkspace(() -> projectService.createProject("QRY", "Query Project"));
  }

  // ---- Velocity -----------------------------------------------------------------------------

  @Test
  void velocityListsMostRecentSprintsFirstWithMovingAverage() {
    Instant now = Instant.now();
    saveSprint("S1", now.minus(30, ChronoUnit.DAYS), new SprintSnapshot(3, 2, 15, 10));
    saveSprint("S2", now.minus(20, ChronoUnit.DAYS), new SprintSnapshot(4, 4, 20, 20));
    saveSprint("S3", now.minus(10, ChronoUnit.DAYS), new SprintSnapshot(5, 4, 40, 30));

    VelocityResponse last2 = inWorkspace(() -> service.velocity(project.getId(), 2));
    assertEquals(2, last2.sprints().size());
    assertEquals("S3", last2.sprints().get(0).name());
    assertEquals("S2", last2.sprints().get(1).name());
    assertEquals(25.0, last2.averageVelocity()); // (30 + 20) / 2

    VelocityResponse all = inWorkspace(() -> service.velocity(project.getId(), 5));
    assertEquals(3, all.sprints().size());
    assertEquals(20.0, all.averageVelocity()); // (10 + 20 + 30) / 3

    var s3 = all.sprints().get(0);
    assertEquals(40L, s3.committedPoints());
    assertEquals(30L, s3.completedPoints());
    assertEquals(10L, s3.spilloverPoints());
    assertEquals(0, new java.math.BigDecimal("0.2500").compareTo(s3.spilloverRate()));
  }

  @Test
  void velocityIsEmptyForProjectWithoutClosedSprints() {
    VelocityResponse response = inWorkspace(() -> service.velocity(project.getId(), 5));

    assertTrue(response.sprints().isEmpty());
    assertNull(response.averageVelocity());
  }

  // ---- Throughput ---------------------------------------------------------------------------

  @Test
  void throughputCountsTasksPerIsoWeekAndZeroFillsEmptyWeeks() {
    Instant now = Instant.now();
    done(now.minusSeconds(10));
    done(now.minusSeconds(20));
    done(now.minus(8, ChronoUnit.DAYS));
    // Done'a girip cikan gorev tamamlanmis SAYILMAZ (reopen).
    UUID reopened = UUID.randomUUID();
    status(reopened, TaskStatus.DONE, now.minus(2, ChronoUnit.DAYS));
    status(reopened, TaskStatus.TO_DO, now.minus(1, ChronoUnit.DAYS));
    // Pencere disi.
    done(now.minus(200, ChronoUnit.DAYS));

    ThroughputResponse response = inWorkspace(() -> service.throughput(project.getId(), 4));

    assertEquals(4, response.weeks().size());
    LocalDate currentWeek = weekStart(now);
    assertEquals(currentWeek, response.weeks().get(3).weekStart());
    assertEquals(currentWeek.minusWeeks(3), response.weeks().get(0).weekStart());

    Map<LocalDate, Long> expected = new HashMap<>();
    expected.merge(weekStart(now), 2L, Long::sum);
    expected.merge(weekStart(now.minus(8, ChronoUnit.DAYS)), 1L, Long::sum);
    for (WeeklyThroughput week : response.weeks()) {
      assertEquals(
          expected.getOrDefault(week.weekStart(), 0L),
          week.completedTasks(),
          "hafta " + week.weekStart());
    }
  }

  // ---- Cycle Time ---------------------------------------------------------------------------

  @Test
  void cycleTimeReportsDistributionOfDefinedCycleTimesInWindow() {
    // Milisaniyeye kesilir: gercek zaman damgalari DB NOW()'undan (mikrosaniye) gelir.
    // Instant.now()
    // alt-mikrosaniye basamak tasirsa, veritabanindan okunan (yuvarlanmis) In Progress zamani ile
    // olaydaki (yuvarlanmamis) Done zamani arasindaki fark 99,9999995 sn olur ve getSeconds() 99
    // verip testi ~yariya yakin ihtimalle kirar (700 ns ile deterministik olarak dogrulandi).
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    for (long seconds : new long[] {100, 200, 300, 400}) {
      UUID task = UUID.randomUUID();
      status(task, TaskStatus.IN_PROGRESS, now.minusSeconds(3600 + seconds));
      status(task, TaskStatus.DONE, now.minusSeconds(3600));
    }
    // In Progress'e hic girmeden Done: cycle time TANIMSIZ, orneklem disi.
    done(now.minusSeconds(60));
    // Pencere disinda tamamlanan.
    UUID old = UUID.randomUUID();
    status(old, TaskStatus.IN_PROGRESS, now.minus(61, ChronoUnit.DAYS));
    status(old, TaskStatus.DONE, now.minus(60, ChronoUnit.DAYS));

    CycleTimeResponse response = inWorkspace(() -> service.cycleTime(project.getId(), 30));

    assertEquals(30, response.days());
    assertEquals(4L, response.sampleSize());
    assertEquals(250.0, response.averageSeconds(), 0.001);
    assertEquals(250.0, response.medianSeconds(), 0.001);
    assertEquals(355.0, response.p85Seconds(), 0.001); // 300 + 0.55 * 100
    assertEquals(385.0, response.p95Seconds(), 0.001); // 300 + 0.85 * 100
  }

  @Test
  void cycleTimeStatsAreNullWithoutSamples() {
    CycleTimeResponse response = inWorkspace(() -> service.cycleTime(project.getId(), 30));

    assertEquals(0L, response.sampleSize());
    assertNull(response.averageSeconds());
    assertNull(response.medianSeconds());
    assertNull(response.p85Seconds());
    assertNull(response.p95Seconds());
  }

  // ---- Tenant izolasyonu / 404 ---------------------------------------------------------------

  @Test
  void unknownAndForeignProjectsAreNotFound() {
    UUID otherWorkspace = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(otherWorkspace, "Other WS"));
    Project foreign =
        tenantExecutor.runAs(otherWorkspace, () -> projectService.createProject("OTH", "Other"));

    // Baska tenant'in projesi (RLS) ve hic olmayan proje AYNI 404'u verir: varlik bilgisi sizmaz.
    assertThrows(
        ResourceNotFoundException.class,
        () -> inWorkspace(() -> service.velocity(foreign.getId(), 5)));
    assertThrows(
        ResourceNotFoundException.class,
        () -> inWorkspace(() -> service.throughput(foreign.getId(), 4)));
    assertThrows(
        ResourceNotFoundException.class,
        () -> inWorkspace(() -> service.cycleTime(foreign.getId(), 30)));
    assertThrows(
        ResourceNotFoundException.class,
        () -> inWorkspace(() -> service.velocity(UUID.randomUUID(), 5)));
  }

  // ---- yardimcilar --------------------------------------------------------------------------

  private static LocalDate weekStart(Instant instant) {
    return instant
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  private void saveSprint(String name, Instant completedAt, SprintSnapshot snapshot) {
    SprintAnalytics analytics =
        SprintAnalytics.create(UUID.randomUUID(), workspaceId, project.getId());
    analytics.recalculate(name, completedAt, snapshot, Instant.now());
    inTenant(workspaceId, () -> sprintAnalyticsRepository.save(analytics));
  }

  /** Yeni bir gorev "In Progress"e girmeden dogrudan Done olur. */
  private void done(Instant at) {
    status(UUID.randomUUID(), TaskStatus.DONE, at);
  }

  private void status(UUID taskId, String newStatus, Instant at) {
    cycleTimeConsumer.onMessage(
        ("{\"eventId\":\"%s\",\"eventType\":\"TASK_STATUS_UPDATED\",\"schemaVersion\":1,"
                + "\"timestamp\":\"%s\",\"aggregateId\":\"%s\",\"workspaceId\":\"%s\","
                + "\"payload\":{\"taskId\":\"%s\",\"projectId\":\"%s\",\"oldStatus\":\"x\","
                + "\"newStatus\":\"%s\"}}")
            .formatted(
                UUID.randomUUID(), at, taskId, workspaceId, taskId, project.getId(), newStatus));
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
          entityManager
              .createNativeQuery("SELECT set_config('app.current_workspace_id', ?1, true)")
              .setParameter(1, tenant.toString())
              .getSingleResult();
          return action.get();
        });
  }
}
