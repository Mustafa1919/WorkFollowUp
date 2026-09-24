package com.app.tracker.goal.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Donem hedefi (V21__goals.sql). RLS'e tabidir.
 *
 * <p>Tek seviyedir: OKR'deki Objective/Key Result hiyerarsisi BILEREK yoktur (bkz. migration ust
 * yorumu). {@code projectId} null ise hedef workspace geneli, dolu ise yalniz o projeye aittir.
 * {@code quarter} null ise YILLIK hedeftir.
 */
@Entity
@Table(name = "goals")
@Getter
@NoArgsConstructor
public class Goal {

  @Id private UUID id;

  private UUID workspaceId;

  /** {@code null} = workspace geneli. */
  private UUID projectId;

  private short periodYear;

  /** {@code null} = yillik hedef, aksi halde 1-4. */
  private Short periodQuarter;

  private String title;

  @Enumerated(EnumType.STRING)
  private GoalMetricType metricType;

  private long targetValue;

  /** Yalniz {@link GoalMetricType#CUSTOM} icin dolu (DB CHECK ile de zorlanir). */
  private Long manualValue;

  private Instant createdAt;

  private Instant updatedAt;

  public static Goal of(
      UUID id,
      UUID workspaceId,
      UUID projectId,
      int periodYear,
      Integer periodQuarter,
      String title,
      GoalMetricType metricType,
      long targetValue) {
    Goal goal = new Goal();
    goal.id = id;
    goal.workspaceId = workspaceId;
    goal.projectId = projectId;
    goal.periodYear = (short) periodYear;
    goal.periodQuarter = periodQuarter == null ? null : periodQuarter.shortValue();
    goal.title = title;
    goal.metricType = metricType;
    goal.targetValue = targetValue;
    // CUSTOM hedef 0'dan baslar; otomatik metrikte kolon NULL kalmali (DB CHECK).
    goal.manualValue = metricType == GoalMetricType.CUSTOM ? 0L : null;
    goal.createdAt = Instant.now();
    goal.updatedAt = goal.createdAt;
    return goal;
  }

  /**
   * Metrik tipi ve donem DEGISTIRILEMEZ: ikisi de hedefin kimligidir, degistirilmesi gecmis
   * ilerlemeyi sessizce baska bir seye ait hale getirirdi (yeni hedef acilmali). Degisebilen yalniz
   * baslik, hedef deger ve kapsam projesidir.
   */
  public void edit(String title, long targetValue, UUID projectId) {
    this.title = title;
    this.targetValue = targetValue;
    this.projectId = projectId;
    this.updatedAt = Instant.now();
  }

  /** Yalniz CUSTOM hedefte cagrilir; cagiran dogrular. */
  public void updateManualValue(long value) {
    this.manualValue = value;
    this.updatedAt = Instant.now();
  }
}
