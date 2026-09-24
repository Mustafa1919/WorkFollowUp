package com.app.tracker.report.service;

import com.app.tracker.core.datasource.ReadReplica;
import com.app.tracker.goal.model.Goal;
import com.app.tracker.goal.model.GoalMetricType;
import com.app.tracker.goal.service.GoalService;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.report.dto.PeriodReportResponse;
import com.app.tracker.report.dto.PeriodReportResponse.Comparison;
import com.app.tracker.report.dto.PeriodReportResponse.GoalProgress;
import com.app.tracker.report.dto.PeriodReportResponse.MonthlyPoint;
import com.app.tracker.report.dto.PeriodReportResponse.PeriodInfo;
import com.app.tracker.report.dto.PeriodReportResponse.ProjectSummary;
import com.app.tracker.report.dto.PeriodReportResponse.Totals;
import com.app.tracker.report.model.ReportPeriod;
import com.app.tracker.report.repository.PeriodReportRepository;
import com.app.tracker.report.repository.PeriodReportRepository.CompletionTotals;
import com.app.tracker.report.repository.PeriodReportRepository.MonthlyCompletion;
import com.app.tracker.report.repository.PeriodReportRepository.ProjectBreakdownRow;
import com.app.tracker.report.repository.PeriodReportRepository.SprintTotals;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Yillik/ceyreklik rapor (RAKIP_ANALIZI.md Bolum 3, Hedefler.md). Workspace genelinde calisir,
 * istege bagli proje suzgeci alir.
 *
 * <p>{@link ReadReplica}: {@code AnalyticsQueryService} ile ayni gerekce — rapor tamamen read
 * model'lerden beslenir ve replikasyon gecikmesine toleranslidir (donem raporu zaten gecmis bir
 * kesittir).
 */
@Service
@ReadReplica
public class PeriodReportService {

  private final PeriodReportRepository reportRepository;
  private final GoalService goalService;
  private final ProjectRepository projectRepository;
  private final ZoneId businessZone;

  public PeriodReportService(
      PeriodReportRepository reportRepository,
      GoalService goalService,
      ProjectRepository projectRepository,
      Clock businessClock) {
    this.reportRepository = reportRepository;
    this.goalService = goalService;
    this.projectRepository = projectRepository;
    this.businessZone = businessClock.getZone();
  }

  @Transactional(readOnly = true)
  public PeriodReportResponse report(ReportPeriod period, List<UUID> projectIds) {
    List<UUID> filter = (projectIds == null || projectIds.isEmpty()) ? null : projectIds;
    Instant from = period.startInstant(businessZone);
    Instant to = period.endInstantExclusive(businessZone);

    CompletionTotals totals = reportRepository.completionTotals(from, to, filter);
    SprintTotals sprints = reportRepository.sprintTotals(from, to, filter);
    List<ProjectBreakdownRow> breakdown = reportRepository.projectBreakdown(from, to, filter);
    List<MonthlyPoint> months = months(period, from, to, filter);

    ReportPeriod previous = period.previous();
    CompletionTotals previousTotals =
        reportRepository.completionTotals(
            previous.startInstant(businessZone),
            previous.endInstantExclusive(businessZone),
            filter);

    return new PeriodReportResponse(
        info(period),
        info(previous),
        new Totals(
            totals.completedTasks(),
            totals.completedPoints(),
            totals.cycleTimeAverageSeconds(),
            totals.cycleTimeMedianSeconds(),
            totals.cycleTimeP85Seconds(),
            totals.cycleTimeSample(),
            sprints.sprintCount(),
            sprints.committedPoints(),
            sprints.completedPoints(),
            sprints.averageVelocity()),
        new Comparison(
            previousTotals.completedTasks(),
            previousTotals.completedPoints(),
            previousTotals.cycleTimeMedianSeconds()),
        breakdown.stream()
            .map(
                row ->
                    new ProjectSummary(
                        row.projectId(),
                        row.projectKey(),
                        row.projectName(),
                        row.completedTasks(),
                        row.completedPoints(),
                        row.cycleTimeMedianSeconds(),
                        row.cycleTimeSample()))
            .toList(),
        months,
        goalProgress(period, from, to, filter, totals, breakdown),
        filter != null);
  }

  /** Bos aylar 0 ile doldurulur ({@code AnalyticsQueryService.throughput} ile ayni desen). */
  private List<MonthlyPoint> months(
      ReportPeriod period, Instant from, Instant to, List<UUID> filter) {
    Map<LocalDate, MonthlyCompletion> byMonth =
        reportRepository.monthlyCompletion(from, to, filter, businessZone.getId()).stream()
            .collect(
                Collectors.toMap(
                    MonthlyCompletion::month, Function.identity(), (a, b) -> a, HashMap::new));
    List<MonthlyPoint> months = new ArrayList<>(period.monthCount());
    LocalDate month = period.startDate();
    for (int i = 0; i < period.monthCount(); i++) {
      MonthlyCompletion row = byMonth.get(month);
      months.add(
          new MonthlyPoint(
              month,
              row == null ? 0L : row.completedTasks(),
              row == null ? 0L : row.completedPoints()));
      month = month.plusMonths(1);
    }
    return months;
  }

  /**
   * Hedef ilerlemesi proje SUZGECINDEN ETKILENMEZ: hedef mutlak bir taahhuttur, raporu daraltmak
   * onu "yaklasmis" gostermemeli. Suzgec varken kapsam sayilari ayrica (suzgecsiz) sorgulanir;
   * suzgec yokken zaten hesaplanmis olanlar yeniden kullanilir — hedef sayisindan BAGIMSIZ olarak
   * en fazla iki ek sorgu (N+1 yok).
   */
  private List<GoalProgress> goalProgress(
      ReportPeriod period,
      Instant from,
      Instant to,
      List<UUID> filter,
      CompletionTotals filteredTotals,
      List<ProjectBreakdownRow> filteredBreakdown) {
    List<Goal> goals = goalService.listByPeriod(period);
    if (goals.isEmpty()) {
      return List.of();
    }
    CompletionTotals workspaceTotals =
        filter == null ? filteredTotals : reportRepository.completionTotals(from, to, null);
    Map<UUID, ProjectBreakdownRow> byProject =
        (filter == null ? filteredBreakdown : reportRepository.projectBreakdown(from, to, null))
            .stream()
                .collect(
                    Collectors.toMap(
                        ProjectBreakdownRow::projectId,
                        Function.identity(),
                        (a, b) -> a,
                        LinkedHashMap::new));
    Map<UUID, String> projectNames = projectNames(goals);

    List<GoalProgress> progress = new ArrayList<>(goals.size());
    for (Goal goal : goals) {
      long current = currentValue(goal, workspaceTotals, byProject);
      progress.add(
          new GoalProgress(
              goal.getId(),
              goal.getTitle(),
              goal.getMetricType(),
              goal.getTargetValue(),
              current,
              percent(current, goal.getTargetValue()),
              goal.getProjectId(),
              goal.getProjectId() == null ? null : projectNames.get(goal.getProjectId())));
    }
    return progress;
  }

  private static long currentValue(
      Goal goal, CompletionTotals workspaceTotals, Map<UUID, ProjectBreakdownRow> byProject) {
    if (goal.getMetricType() == GoalMetricType.CUSTOM) {
      return goal.getManualValue() == null ? 0L : goal.getManualValue();
    }
    boolean tasks = goal.getMetricType() == GoalMetricType.COMPLETED_TASKS;
    if (goal.getProjectId() == null) {
      return tasks ? workspaceTotals.completedTasks() : workspaceTotals.completedPoints();
    }
    ProjectBreakdownRow row = byProject.get(goal.getProjectId());
    if (row == null) {
      return 0L; // projede o donemde hic tamamlanan gorev yok
    }
    return tasks ? row.completedTasks() : row.completedPoints();
  }

  private Map<UUID, String> projectNames(List<Goal> goals) {
    List<UUID> ids = goals.stream().map(Goal::getProjectId).filter(Objects::nonNull).toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> names = new HashMap<>();
    for (Project project : projectRepository.findAllById(ids)) {
      names.put(project.getId(), project.getName());
    }
    return names;
  }

  private static int percent(long current, long target) {
    if (target <= 0) {
      return 0;
    }
    return (int) Math.min(100L, Math.round(current * 100.0 / target));
  }

  private static PeriodInfo info(ReportPeriod period) {
    return new PeriodInfo(
        period.year(), period.quarter(), period.label(), period.startDate(), period.endDate());
  }
}
