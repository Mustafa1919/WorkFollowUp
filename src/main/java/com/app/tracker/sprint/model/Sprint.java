package com.app.tracker.sprint.model;

import com.app.tracker.core.exception.BusinessRuleException;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DATABASE_SCHEMA.md 2.5 — RLS'e tabidir (V2/V6). Durum gecisleri (planned -> active -> completed)
 * yalnizca bu sinifin metotlariyla yapilir; servis katmani gecersiz gecisi ayrica kontrol etmek
 * zorunda kalmaz.
 */
@Entity
@Table(name = "sprints")
@Getter
@NoArgsConstructor
public class Sprint {

  @Id private UUID id;

  private UUID workspaceId;

  private UUID projectId;

  private String name;

  private String goal;

  private String status;

  private LocalDate startDate;

  private LocalDate endDate;

  private Instant createdAt;

  private Instant startedAt;

  private Instant completedAt;

  public static Sprint planned(
      UUID id,
      UUID workspaceId,
      UUID projectId,
      String name,
      String goal,
      LocalDate startDate,
      LocalDate endDate) {
    Sprint sprint = new Sprint();
    sprint.id = id;
    sprint.workspaceId = workspaceId;
    sprint.projectId = projectId;
    sprint.name = name;
    sprint.goal = goal;
    sprint.status = SprintStatus.PLANNED;
    sprint.startDate = startDate;
    sprint.endDate = endDate;
    sprint.createdAt = Instant.now();
    return sprint;
  }

  public void start(Instant now) {
    if (!SprintStatus.PLANNED.equals(status)) {
      throw new BusinessRuleException("Yalnizca 'planned' durumdaki sprint baslatilabilir.");
    }
    this.status = SprintStatus.ACTIVE;
    this.startedAt = now;
  }

  public void complete(Instant now) {
    if (!SprintStatus.ACTIVE.equals(status)) {
      throw new BusinessRuleException("Yalnizca 'active' durumdaki sprint kapatilabilir.");
    }
    this.status = SprintStatus.COMPLETED;
    this.completedAt = now;
  }

  public boolean isCompleted() {
    return SprintStatus.COMPLETED.equals(status);
  }
}
