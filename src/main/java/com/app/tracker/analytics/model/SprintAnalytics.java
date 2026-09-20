package com.app.tracker.analytics.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 1.1 — sprint basina Velocity / Spillover. RLS'e tabi bir READ
 * MODEL'dir (V12).
 *
 * <p>Tanimlar (dokumanin acik biraktigi noktalar burada sabitlenir):
 *
 * <ul>
 *   <li>committed = sprint KAPANDIGI andaki uyeler (sprint ortasinda eklenenler dahil; ilk taahhut
 *       ile sonradan eklenen isi ayirmak icin ayrica {@code startedAt} kesiti gerekir — simdilik
 *       kapsam disi).
 *   <li>completed (Velocity) = committed icinden kesitte Done olanlarin story point toplami.
 *   <li>Story point'i olmayan gorev 0 puan sayilir; bu yuzden gorev sayilari da tutulur (puansiz
 *       bir sprint'in velocity'si 0 gorunup gercekte is yapilmis olabilir).
 *   <li>spilloverRate = (committed - completed) / committed; committed = 0 ise {@code null}.
 * </ul>
 *
 * <p>Ayni sprint icin yeniden hesaplama ayni girdiyle ayni sonucu verir (kesit sabit, tarihce
 * append-only), bu yuzden upsert guvenlidir.
 */
@Entity
@Table(name = "sprint_analytics")
@Getter
@NoArgsConstructor
public class SprintAnalytics {

  private static final int RATE_SCALE = 4;

  @Id private UUID sprintId;

  private UUID workspaceId;

  private UUID projectId;

  private String sprintName;

  private Instant completedAt;

  private int committedTasks;

  private int completedTasks;

  private long committedPoints;

  private long completedPoints;

  private BigDecimal spilloverRate;

  private Instant calculatedAt;

  public static SprintAnalytics create(UUID sprintId, UUID workspaceId, UUID projectId) {
    SprintAnalytics analytics = new SprintAnalytics();
    analytics.sprintId = sprintId;
    analytics.workspaceId = workspaceId;
    analytics.projectId = projectId;
    return analytics;
  }

  public void recalculate(
      String sprintName, Instant completedAt, SprintSnapshot snapshot, Instant now) {
    this.sprintName = sprintName;
    this.completedAt = completedAt;
    this.committedTasks = snapshot.committedTasks();
    this.completedTasks = snapshot.completedTasks();
    this.committedPoints = snapshot.committedPoints();
    this.completedPoints = snapshot.completedPoints();
    this.spilloverRate =
        committedPoints == 0
            ? null
            : BigDecimal.valueOf(committedPoints - completedPoints)
                .divide(BigDecimal.valueOf(committedPoints), RATE_SCALE, RoundingMode.HALF_UP);
    this.calculatedAt = now;
  }

  public long getSpilloverPoints() {
    return committedPoints - completedPoints;
  }
}
