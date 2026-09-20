package com.app.tracker.analytics.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Proje bazli, zaman penceresi uzerinden hesaplanan okuma sorgulari (Weekly Throughput, Cycle Time
 * dagilimi). Ikisi de {@code task_analytics} read model'inden beslenir: Cycle Time projeksiyonu
 * zaten {@code task_events}'teki status gecislerinden turetildigi icin "Done olayi" bilgisi yeniden
 * hesaplanmaz; ustelik "Done'dan cikan (reopen) gorev tamamlanmis sayilmaz" kurali orada hazirdir
 * (bkz. {@code TaskAnalytics}).
 *
 * <p>Cagiran, RLS baglamini kurmus bir transaction icinde olmalidir.
 */
@Repository
public class ProjectMetricsRepository {

  private final EntityManager entityManager;

  public ProjectMetricsRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /**
   * Haftalik tamamlanan gorev sayisi. Hafta = ISO haftasi (Pazartesi baslar), UTC. Tamamlanma =
   * gorevin SON Done gecisi: Done'dan sonra yeniden acilip tekrar kapanan gorev yalnizca son
   * kapandigi haftada sayilir. Yalnizca tamamlanma olan haftalar doner (bos haftalari cagiran
   * doldurur).
   *
   * @return hafta baslangici (Pazartesi) -> sayi
   */
  public Map<LocalDate, Long> weeklyCompletedTasks(UUID projectId, Instant since) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT to_char(date_trunc('week', done_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD'), "
                    + "COUNT(*) FROM task_analytics "
                    + "WHERE project_id = ?1 AND done_at IS NOT NULL AND done_at >= ?2 "
                    + "GROUP BY 1 ORDER BY 1")
            .setParameter(1, projectId)
            .setParameter(2, since)
            .getResultList();
    Map<LocalDate, Long> weeks = new LinkedHashMap<>();
    for (Object[] row : rows) {
      weeks.put(LocalDate.parse((String) row[0]), ((Number) row[1]).longValue());
    }
    return weeks;
  }

  /**
   * Pencerede tamamlanan ve cycle time'i TANIMLI gorevlerin dagilimi ({@code In Progress}'e hic
   * girmeden Done olanlar tanimsizdir, dahil edilmez). Ortalama tek basina yaniltici oldugu icin
   * (sag kuyruk) yuzdelikler de doner.
   */
  public CycleTimeStats cycleTimeStats(UUID projectId, Instant since) {
    Object[] row =
        (Object[])
            entityManager
                .createNativeQuery(
                    "SELECT COUNT(*), AVG(cycle_time_seconds), "
                        + "percentile_cont(0.5) WITHIN GROUP "
                        + "(ORDER BY CAST(cycle_time_seconds AS double precision)), "
                        + "percentile_cont(0.85) WITHIN GROUP "
                        + "(ORDER BY CAST(cycle_time_seconds AS double precision)), "
                        + "percentile_cont(0.95) WITHIN GROUP "
                        + "(ORDER BY CAST(cycle_time_seconds AS double precision)) "
                        + "FROM task_analytics "
                        + "WHERE project_id = ?1 AND done_at >= ?2 AND cycle_time_seconds IS NOT NULL")
                .setParameter(1, projectId)
                .setParameter(2, since)
                .getSingleResult();
    return new CycleTimeStats(
        ((Number) row[0]).longValue(),
        toDouble(row[1]),
        toDouble(row[2]),
        toDouble(row[3]),
        toDouble(row[4]));
  }

  private static Double toDouble(Object value) {
    return value == null ? null : ((Number) value).doubleValue();
  }

  /** Ornek yoksa ({@code sampleSize == 0}) tum istatistikler {@code null}. */
  public record CycleTimeStats(
      long sampleSize, Double average, Double median, Double p85, Double p95) {}
}
