package com.app.tracker.report.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Donemsel (yillik/ceyreklik) rapor sorgulari — WORKSPACE genelinde, istege bagli proje suzgeciyle.
 *
 * <p>Kaynak, mevcut read model'lerdir ({@code task_analytics}, {@code sprint_analytics}): raporun
 * hicbir sayisi {@code task_events}'ten yeniden hesaplanmaz. Bunun onemli bir sonucu var —
 * "tamamlanma" tanimi Throughput/Cycle Time ile BIREBIR AYNIDIR (gorevin SON Done gecisi; Done'dan
 * cikip yeniden acilan gorev sayilmaz, bkz. {@code TaskAnalytics}), yani rapor ile proje analitigi
 * birbiriyle celismez.
 *
 * <p>Story point {@code tasks.custom_fields ->> 'story_point'} uzerinden okunur ({@code
 * TaskCustomFieldRepository} ile ayni kaynak); puansiz gorev 0 sayilir — {@code SprintAnalytics}'in
 * velocity kuraliyla tutarli. Soft-delete edilmis gorevler ({@code tasks.deleted_at}) rapora
 * girmez; {@code task_analytics} satiri {@code TASK_DELETED} ile zaten silinir, bu JOIN ikinci
 * savunma hattidir (DLT replay'i silinmis gorevin satirini geri getirebilir — bkz. Ilerleme.md
 * 2026-09-22 bilinen sinir).
 *
 * <p>Cagiran, RLS baglamini kurmus bir transaction icinde olmalidir.
 */
@Repository
public class PeriodReportRepository {

  private static final String COMPLETED_FROM =
      " FROM task_analytics ta"
          + " JOIN tasks t ON t.id = ta.task_id AND t.deleted_at IS NULL"
          + " WHERE ta.done_at >= ?1 AND ta.done_at < ?2";

  /**
   * Puansiz gorev 0; bozuk/sayisal olmayan deger de 0 sayilir (rapor bir gorev yuzunden patlamaz).
   */
  private static final String POINTS =
      "COALESCE(SUM(COALESCE("
          + "CASE WHEN t.custom_fields ->> 'story_point' ~ '^[0-9]+$'"
          + " THEN CAST(t.custom_fields ->> 'story_point' AS bigint) END, 0)), 0)";

  private static final String CYCLE = "CAST(ta.cycle_time_seconds AS double precision)";

  private final EntityManager entityManager;

  public PeriodReportRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** Donemin toplamlari. Proje suzgeci {@code null}/bos ise workspace'in TUM projeleri. */
  public CompletionTotals completionTotals(
      Instant from, Instant toExclusive, List<UUID> projectIds) {
    String sql =
        "SELECT COUNT(*), "
            + POINTS
            + ", AVG("
            + CYCLE
            + "), percentile_cont(0.5) WITHIN GROUP (ORDER BY "
            + CYCLE
            + "), percentile_cont(0.85) WITHIN GROUP (ORDER BY "
            + CYCLE
            + "), COUNT(ta.cycle_time_seconds)"
            + COMPLETED_FROM
            + projectFilter(projectIds, 3);
    Object[] row = (Object[]) bind(sql, from, toExclusive, projectIds).getSingleResult();
    return new CompletionTotals(
        longValue(row[0]),
        longValue(row[1]),
        doubleValue(row[2]),
        doubleValue(row[3]),
        doubleValue(row[4]),
        longValue(row[5]));
  }

  /** Proje kirilimi — tamamlanan gorev sayisina gore azalan. */
  public List<ProjectBreakdownRow> projectBreakdown(
      Instant from, Instant toExclusive, List<UUID> projectIds) {
    String sql =
        "SELECT CAST(p.id AS varchar), p.key, p.name, COUNT(*), "
            + POINTS
            + ", percentile_cont(0.5) WITHIN GROUP (ORDER BY "
            + CYCLE
            + "), COUNT(ta.cycle_time_seconds)"
            + " FROM task_analytics ta"
            + " JOIN tasks t ON t.id = ta.task_id AND t.deleted_at IS NULL"
            + " JOIN projects p ON p.id = ta.project_id"
            + " WHERE ta.done_at >= ?1 AND ta.done_at < ?2"
            + projectFilter(projectIds, 3)
            + " GROUP BY p.id, p.key, p.name ORDER BY COUNT(*) DESC, p.name ASC";
    @SuppressWarnings("unchecked")
    List<Object[]> rows = bind(sql, from, toExclusive, projectIds).getResultList();
    List<ProjectBreakdownRow> result = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      result.add(
          new ProjectBreakdownRow(
              UUID.fromString((String) row[0]),
              (String) row[1],
              (String) row[2],
              longValue(row[3]),
              longValue(row[4]),
              doubleValue(row[5]),
              longValue(row[6])));
    }
    return result;
  }

  /**
   * Aylik kirilim. Ay siniri IS saat diliminde hesaplanir ({@code AT TIME ZONE}), bu yuzden
   * "Temmuz" Istanbul'un Temmuz'udur. Yalniz tamamlanma OLAN aylar doner; bos aylari cagiran
   * doldurur ({@code AnalyticsQueryService.throughput} ile ayni desen).
   */
  public List<MonthlyCompletion> monthlyCompletion(
      Instant from, Instant toExclusive, List<UUID> projectIds, String zoneId) {
    String sql =
        "SELECT to_char(date_trunc('month', ta.done_at AT TIME ZONE ?3), 'YYYY-MM-DD'), COUNT(*), "
            + POINTS
            + COMPLETED_FROM
            + projectFilter(projectIds, 4)
            + " GROUP BY 1 ORDER BY 1";
    Query query =
        entityManager
            .createNativeQuery(sql)
            .setParameter(1, from)
            .setParameter(2, toExclusive)
            .setParameter(3, zoneId);
    bindProjectIds(query, projectIds, 4);
    @SuppressWarnings("unchecked")
    List<Object[]> rows = query.getResultList();
    List<MonthlyCompletion> result = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      result.add(
          new MonthlyCompletion(
              LocalDate.parse((String) row[0]), longValue(row[1]), longValue(row[2])));
    }
    return result;
  }

  /**
   * Donemde KAPANAN sprint'lerin toplamlari. {@code sprint_analytics.completed_at} sprint'in
   * degismez kesit zamanidir (bkz. {@code SprintAnalytics}), bu yuzden donem atamasi kararlidir.
   */
  public SprintTotals sprintTotals(Instant from, Instant toExclusive, List<UUID> projectIds) {
    String sql =
        "SELECT COUNT(*), COALESCE(SUM(completed_points), 0), COALESCE(SUM(committed_points), 0),"
            + " AVG(CAST(completed_points AS double precision))"
            + " FROM sprint_analytics"
            + " WHERE completed_at >= ?1 AND completed_at < ?2"
            + projectFilterOn("project_id", projectIds, 3);
    Object[] row = (Object[]) bind(sql, from, toExclusive, projectIds).getSingleResult();
    return new SprintTotals(
        longValue(row[0]), longValue(row[1]), longValue(row[2]), doubleValue(row[3]));
  }

  private Query bind(String sql, Instant from, Instant toExclusive, List<UUID> projectIds) {
    Query query =
        entityManager.createNativeQuery(sql).setParameter(1, from).setParameter(2, toExclusive);
    bindProjectIds(query, projectIds, 3);
    return query;
  }

  private String projectFilter(List<UUID> projectIds, int firstOrdinal) {
    return projectFilterOn("ta.project_id", projectIds, firstOrdinal);
  }

  private String projectFilterOn(String column, List<UUID> projectIds, int firstOrdinal) {
    if (projectIds == null || projectIds.isEmpty()) {
      return "";
    }
    StringBuilder sql = new StringBuilder(" AND ").append(column).append(" IN (");
    for (int i = 0; i < projectIds.size(); i++) {
      sql.append(i == 0 ? "?" : ",?").append(firstOrdinal + i);
    }
    return sql.append(')').toString();
  }

  private void bindProjectIds(Query query, List<UUID> projectIds, int firstOrdinal) {
    if (projectIds == null) {
      return;
    }
    for (int i = 0; i < projectIds.size(); i++) {
      query.setParameter(firstOrdinal + i, projectIds.get(i));
    }
  }

  private static long longValue(Object value) {
    return value == null ? 0L : ((Number) value).longValue();
  }

  private static Double doubleValue(Object value) {
    return value == null ? null : ((Number) value).doubleValue();
  }

  /** Cycle time degerleri ornek yoksa {@code null} ({@code cycleTimeSample == 0}). */
  public record CompletionTotals(
      long completedTasks,
      long completedPoints,
      Double cycleTimeAverageSeconds,
      Double cycleTimeMedianSeconds,
      Double cycleTimeP85Seconds,
      long cycleTimeSample) {}

  public record ProjectBreakdownRow(
      UUID projectId,
      String projectKey,
      String projectName,
      long completedTasks,
      long completedPoints,
      Double cycleTimeMedianSeconds,
      long cycleTimeSample) {}

  public record MonthlyCompletion(LocalDate month, long completedTasks, long completedPoints) {}

  public record SprintTotals(
      long sprintCount, long completedPoints, long committedPoints, Double averageVelocity) {}
}
