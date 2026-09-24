package com.app.tracker.report.dto;

import com.app.tracker.goal.model.GoalMetricType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Donemsel rapor cevabi. Tek istekte sayfanin tamami doner (KPI'lar, aylik trend, proje kirilimi,
 * hedef ilerlemeleri): sayfa bunlarin hepsini ayni anda gosterir ve hepsi AYNI donem/suzgec
 * kesitinden hesaplanir — ayri endpoint'lere bolmek, parcalarin farkli anlarda gelmesi yuzunden
 * kendi icinde celisen bir rapor uretme riski tasirdi.
 *
 * @param projectFilterApplied kullanici proje suzgeci uyguladiysa {@code true} — hedef ilerlemeleri
 *     suzgecten ETKILENMEZ (hedef mutlak bir taahhuttur), arayuz bu farki aciklayabilsin diye
 *     bildirilir
 */
public record PeriodReportResponse(
    PeriodInfo period,
    PeriodInfo previousPeriod,
    Totals totals,
    Comparison previousTotals,
    List<ProjectSummary> projects,
    List<MonthlyPoint> months,
    List<GoalProgress> goals,
    boolean projectFilterApplied) {

  /**
   * Liste bilesenleri kopyalanir (SpotBugs EI_EXPOSE_REP) — {@code DependencySummary} (V19) ile
   * ayni desen: record'un getter'i ici degistirilebilir listeyi dogrudan disari vermemeli.
   */
  public PeriodReportResponse {
    projects = List.copyOf(projects);
    months = List.copyOf(months);
    goals = List.copyOf(goals);
  }

  public record PeriodInfo(
      int year, Integer quarter, String label, LocalDate startDate, LocalDate endDate) {}

  /**
   * Cycle time degerleri {@code cycleTimeSample == 0} ise {@code null}; {@code averageVelocity}
   * donemde kapanan sprint yoksa {@code null}.
   */
  public record Totals(
      long completedTasks,
      long completedPoints,
      Double cycleTimeAverageSeconds,
      Double cycleTimeMedianSeconds,
      Double cycleTimeP85Seconds,
      long cycleTimeSample,
      long sprintCount,
      long sprintCommittedPoints,
      long sprintCompletedPoints,
      Double averageVelocity) {}

  /** Onceki donemin yalniz kiyaslanabilir sayilari (sprint metrikleri bilerek disarida). */
  public record Comparison(
      long completedTasks, long completedPoints, Double cycleTimeMedianSeconds) {}

  public record ProjectSummary(
      UUID projectId,
      String projectKey,
      String projectName,
      long completedTasks,
      long completedPoints,
      Double cycleTimeMedianSeconds,
      long cycleTimeSample) {}

  public record MonthlyPoint(LocalDate month, long completedTasks, long completedPoints) {}

  /**
   * @param currentValue otomatik metriklerde read model'den, CUSTOM'da elle girilen deger
   * @param progressPercent 0-100 arasina KIRPILIR (hedefi asmak %100 gosterilir); ham oran icin
   *     {@code currentValue}/{@code targetValue} kullanilabilir
   */
  public record GoalProgress(
      UUID id,
      String title,
      GoalMetricType metricType,
      long targetValue,
      long currentValue,
      int progressPercent,
      UUID projectId,
      String projectName) {}
}
