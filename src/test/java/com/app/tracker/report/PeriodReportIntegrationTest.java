package com.app.tracker.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.analytics.consumer.CycleTimeConsumer;
import com.app.tracker.analytics.model.SprintAnalytics;
import com.app.tracker.analytics.model.SprintSnapshot;
import com.app.tracker.analytics.repository.SprintAnalyticsRepository;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.goal.model.Goal;
import com.app.tracker.goal.model.GoalMetricType;
import com.app.tracker.goal.service.GoalService;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.report.dto.PeriodReportResponse;
import com.app.tracker.report.dto.PeriodReportResponse.GoalProgress;
import com.app.tracker.report.dto.PeriodReportResponse.MonthlyPoint;
import com.app.tracker.report.dto.PeriodReportResponse.ProjectSummary;
import com.app.tracker.report.model.ReportPeriod;
import com.app.tracker.report.service.PeriodReportService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Donemsel rapor (yillik/ceyreklik). Veri, gercek {@code CycleTimeConsumer} ile uretilir — {@code
 * task_analytics} satirlari elle INSERT edilmez, boylece raporun "tamamlanma" tanimi proje
 * analitigiyle ayni kalir.
 *
 * <p>Donem GECMISTE sabit secilir (2025-Q2): testin kosuldugu gun ne olursa olsun sonuc degismez —
 * takvim donemi seciminin asil gerekcesi de budur.
 */
@SpringBootTest
class PeriodReportIntegrationTest extends AbstractIntegrationTest {

  private static final ReportPeriod Q2 = new ReportPeriod(2025, 2);
  private static final ReportPeriod YEAR = new ReportPeriod(2025, null);

  @Autowired private PeriodReportService reportService;
  @Autowired private GoalService goalService;
  @Autowired private CycleTimeConsumer cycleTimeConsumer;
  @Autowired private SprintAnalyticsRepository sprintAnalyticsRepository;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;

  private UUID workspaceId;
  private UUID actorId;
  private Project alpha;
  private Project beta;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Report WS"));
    actorId =
        authService
            .register("report-" + UUID.randomUUID() + "@tracker.local", "Parola123!Guclu", "R")
            .getId();
    alpha = inWorkspace(() -> projectService.createProject("ALP", "Alpha"));
    beta = inWorkspace(() -> projectService.createProject("BET", "Beta"));

    completed(alpha, 5, "2025-04-15T09:00:00Z");
    completed(alpha, null, "2025-05-20T09:00:00Z");
    completed(beta, 3, "2025-05-05T09:00:00Z");
    // Onceki ceyrekte (Q1) tamamlanan — Q2 raporunda yalniz KIYASLAMA tarafinda gorunmeli.
    completed(alpha, 8, "2025-02-10T09:00:00Z");
  }

  @Test
  void workspaceWideReportAggregatesAcrossProjects() {
    PeriodReportResponse report = inWorkspace(() -> reportService.report(Q2, null));

    assertEquals("2025-Q2", report.period().label());
    assertEquals(LocalDate.of(2025, 4, 1), report.period().startDate());
    assertEquals(LocalDate.of(2025, 6, 30), report.period().endDate());
    assertFalse(report.projectFilterApplied());

    assertEquals(3L, report.totals().completedTasks());
    assertEquals(8L, report.totals().completedPoints()); // 5 + 0 (puansiz) + 3

    // Onceki ceyrek (2025-Q1) kiyaslamasi.
    assertEquals("2025-Q1", report.previousPeriod().label());
    assertEquals(1L, report.previousTotals().completedTasks());
    assertEquals(8L, report.previousTotals().completedPoints());
  }

  @Test
  void projectBreakdownIsOrderedByCompletedTasks() {
    PeriodReportResponse report = inWorkspace(() -> reportService.report(Q2, null));

    assertEquals(2, report.projects().size());
    ProjectSummary first = report.projects().get(0);
    assertEquals(alpha.getId(), first.projectId());
    assertEquals("ALP", first.projectKey());
    assertEquals(2L, first.completedTasks());
    assertEquals(5L, first.completedPoints());

    ProjectSummary second = report.projects().get(1);
    assertEquals(beta.getId(), second.projectId());
    assertEquals(1L, second.completedTasks());
    assertEquals(3L, second.completedPoints());
  }

  @Test
  void monthBucketsCoverWholePeriodAndZeroFillEmptyMonths() {
    List<MonthlyPoint> months = inWorkspace(() -> reportService.report(Q2, null)).months();

    assertEquals(3, months.size());
    assertEquals(LocalDate.of(2025, 4, 1), months.get(0).month());
    assertEquals(1L, months.get(0).completedTasks());
    assertEquals(5L, months.get(0).completedPoints());
    assertEquals(LocalDate.of(2025, 5, 1), months.get(1).month());
    assertEquals(2L, months.get(1).completedTasks());
    assertEquals(3L, months.get(1).completedPoints());
    assertEquals(LocalDate.of(2025, 6, 1), months.get(2).month());
    assertEquals(0L, months.get(2).completedTasks());

    assertEquals(12, inWorkspace(() -> reportService.report(YEAR, null)).months().size());
  }

  @Test
  void projectFilterNarrowsTotalsAndBreakdown() {
    PeriodReportResponse report =
        inWorkspace(() -> reportService.report(Q2, List.of(beta.getId())));

    assertTrue(report.projectFilterApplied());
    assertEquals(1L, report.totals().completedTasks());
    assertEquals(3L, report.totals().completedPoints());
    assertEquals(1, report.projects().size());
    assertEquals(beta.getId(), report.projects().get(0).projectId());
    // Kiyaslama da ayni suzgeci kullanir: Q1'deki tamamlanan Alpha'daydi.
    assertEquals(0L, report.previousTotals().completedTasks());
  }

  @Test
  void cycleTimeStatisticsComeFromTheSameReadModel() {
    // In Progress -> Done: cycle time TANIMLI tek gorev.
    Task task = inWorkspace(() -> taskService.createTask(alpha.getId(), "Olculebilir"));
    status(task.getId(), alpha.getId(), TaskStatus.IN_PROGRESS, "2025-06-10T09:00:00Z");
    status(task.getId(), alpha.getId(), TaskStatus.DONE, "2025-06-10T10:00:00Z");

    PeriodReportResponse report = inWorkspace(() -> reportService.report(Q2, null));

    assertEquals(1L, report.totals().cycleTimeSample());
    assertEquals(3600.0, report.totals().cycleTimeMedianSeconds(), 0.001);
    assertNotNull(report.totals().cycleTimeP85Seconds());
  }

  /** Ornek yoksa istatistikler {@code null} olmali (0 degil — "hizliydi" yanilgisi). */
  @Test
  void cycleTimeIsNullWhenNoTaskHasDefinedCycleTime() {
    PeriodReportResponse report = inWorkspace(() -> reportService.report(Q2, null));

    assertEquals(0L, report.totals().cycleTimeSample());
    assertNull(report.totals().cycleTimeMedianSeconds());
    assertNull(report.totals().cycleTimeAverageSeconds());
  }

  @Test
  void softDeletedTasksAreExcludedEvenIfReadModelRowSurvives() {
    Task deleted = inWorkspace(() -> taskService.createTask(alpha.getId(), "Silinen"));
    status(deleted.getId(), alpha.getId(), TaskStatus.DONE, "2025-04-02T09:00:00Z");
    // DLT replay'i silinmis gorevin analitik satirini geri getirebilir (bilinen sinir): read model
    // satiri DURURKEN gorevin soft-delete edilmis olmasi raporu etkilememeli.
    markDeleted(deleted.getId());

    PeriodReportResponse report = inWorkspace(() -> reportService.report(Q2, null));

    assertEquals(3L, report.totals().completedTasks());
  }

  @Test
  void closedSprintsInPeriodAreSummarised() {
    saveSprint("S1", Instant.parse("2025-04-30T12:00:00Z"), new SprintSnapshot(4, 3, 20, 15));
    saveSprint("S2", Instant.parse("2025-06-30T12:00:00Z"), new SprintSnapshot(5, 5, 30, 30));
    // Donem disi: sayilmamali.
    saveSprint("S0", Instant.parse("2025-03-01T12:00:00Z"), new SprintSnapshot(2, 1, 8, 4));

    PeriodReportResponse report = inWorkspace(() -> reportService.report(Q2, null));

    assertEquals(2L, report.totals().sprintCount());
    assertEquals(50L, report.totals().sprintCommittedPoints());
    assertEquals(45L, report.totals().sprintCompletedPoints());
    assertEquals(22.5, report.totals().averageVelocity(), 0.001);
  }

  @Test
  void sprintTotalsAreEmptyWithoutClosedSprints() {
    PeriodReportResponse report = inWorkspace(() -> reportService.report(Q2, null));

    assertEquals(0L, report.totals().sprintCount());
    assertNull(report.totals().averageVelocity());
  }

  // ---- Hedefler -----------------------------------------------------------------------------

  @Test
  void automaticGoalProgressIsDerivedFromTheSameNumbersAsTheReport() {
    inWorkspace(
        () -> goalService.create(Q2, "Workspace geneli", GoalMetricType.COMPLETED_TASKS, 6, null));
    inWorkspace(
        () ->
            goalService.create(
                Q2, "Alpha puan", GoalMetricType.COMPLETED_POINTS, 10, alpha.getId()));

    List<GoalProgress> goals = inWorkspace(() -> reportService.report(Q2, null)).goals();

    assertEquals(2, goals.size());
    GoalProgress workspaceGoal = goals.get(0);
    assertEquals(3L, workspaceGoal.currentValue());
    assertEquals(50, workspaceGoal.progressPercent());
    assertNull(workspaceGoal.projectId());

    GoalProgress projectGoal = goals.get(1);
    assertEquals(5L, projectGoal.currentValue());
    assertEquals(50, projectGoal.progressPercent());
    assertEquals("Alpha", projectGoal.projectName());
  }

  /** Hedef mutlak bir taahhuttur: raporu daraltmak onu "yaklasmis" gostermemeli. */
  @Test
  void goalProgressIgnoresTheProjectFilter() {
    inWorkspace(
        () -> goalService.create(Q2, "Workspace geneli", GoalMetricType.COMPLETED_TASKS, 6, null));

    PeriodReportResponse filtered =
        inWorkspace(() -> reportService.report(Q2, List.of(beta.getId())));

    assertEquals(1L, filtered.totals().completedTasks());
    assertEquals(3L, filtered.goals().get(0).currentValue());
  }

  @Test
  void customGoalProgressIsManualAndCappedAtHundredPercent() {
    Goal goal = inWorkspace(() -> goalService.create(Q2, "Demo", GoalMetricType.CUSTOM, 4, null));

    assertEquals(
        0L, inWorkspace(() -> reportService.report(Q2, null)).goals().get(0).currentValue());

    inWorkspace(() -> goalService.updateProgress(goal.getId(), 9));

    GoalProgress progress = inWorkspace(() -> reportService.report(Q2, null)).goals().get(0);
    assertEquals(9L, progress.currentValue());
    assertEquals(100, progress.progressPercent());
  }

  @Test
  void manualProgressIsRejectedForAutomaticGoals() {
    Goal goal =
        inWorkspace(
            () -> goalService.create(Q2, "Otomatik", GoalMetricType.COMPLETED_TASKS, 4, null));

    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> goalService.updateProgress(goal.getId(), 2)));
  }

  /** Yillik ve ceyreklik hedefler AYRI listelerdir: yillik rapor ceyreklik hedefleri gostermez. */
  @Test
  void yearlyAndQuarterlyGoalsDoNotLeakIntoEachOther() {
    inWorkspace(() -> goalService.create(Q2, "Ceyrek", GoalMetricType.COMPLETED_TASKS, 4, null));
    inWorkspace(() -> goalService.create(YEAR, "Yil", GoalMetricType.COMPLETED_TASKS, 40, null));

    assertEquals(
        "Ceyrek", inWorkspace(() -> reportService.report(Q2, null)).goals().get(0).title());
    List<GoalProgress> yearly = inWorkspace(() -> reportService.report(YEAR, null)).goals();
    assertEquals(1, yearly.size());
    assertEquals("Yil", yearly.get(0).title());
    // Yillik donem Q1'deki gorevi de kapsar.
    assertEquals(4L, yearly.get(0).currentValue());
  }

  // ---- yardimcilar --------------------------------------------------------------------------

  /** Gercek bir gorev yaratip (istege bagli puanla) verilen anda Done'a tasir. */
  private void completed(Project project, Integer storyPoint, String doneAt) {
    Task task =
        inWorkspace(() -> taskService.createTask(project.getId(), "T-" + UUID.randomUUID()));
    if (storyPoint != null) {
      inWorkspace(() -> taskService.updateStoryPoint(task.getId(), storyPoint, actorId));
    }
    status(task.getId(), project.getId(), TaskStatus.DONE, doneAt);
  }

  private void status(UUID taskId, UUID projectId, String newStatus, String at) {
    cycleTimeConsumer.onMessage(
        ("{\"eventId\":\"%s\",\"eventType\":\"TASK_STATUS_UPDATED\",\"schemaVersion\":1,"
                + "\"timestamp\":\"%s\",\"aggregateId\":\"%s\",\"workspaceId\":\"%s\","
                + "\"payload\":{\"taskId\":\"%s\",\"projectId\":\"%s\",\"oldStatus\":\"x\","
                + "\"newStatus\":\"%s\"}}")
            .formatted(UUID.randomUUID(), at, taskId, workspaceId, taskId, projectId, newStatus));
  }

  private void markDeleted(UUID taskId) {
    inTenant(
        workspaceId,
        () ->
            entityManager
                .createNativeQuery("UPDATE tasks SET deleted_at = NOW() WHERE id = ?1")
                .setParameter(1, taskId)
                .executeUpdate());
  }

  private void saveSprint(String name, Instant completedAt, SprintSnapshot snapshot) {
    SprintAnalytics analytics =
        SprintAnalytics.create(UUID.randomUUID(), workspaceId, alpha.getId());
    analytics.recalculate(name, completedAt, snapshot, null, Instant.now());
    inTenant(workspaceId, () -> sprintAnalyticsRepository.save(analytics));
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  /** TransactionTemplate AOP'tan gecmedigi icin tenant context'i elle kurulur. */
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
