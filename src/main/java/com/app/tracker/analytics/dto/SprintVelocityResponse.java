package com.app.tracker.analytics.dto;

import com.app.tracker.analytics.model.SprintAnalytics;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Tek sprint'in kapanis kesitindeki metrikleri. {@code spilloverRate} committedPoints = 0 ise
 * {@code null}'dur (bkz. {@link SprintAnalytics}).
 */
public record SprintVelocityResponse(
    UUID sprintId,
    String name,
    Instant completedAt,
    int committedTasks,
    int completedTasks,
    long committedPoints,
    long completedPoints,
    long spilloverPoints,
    BigDecimal spilloverRate,
    Instant calculatedAt) {

  public static SprintVelocityResponse from(SprintAnalytics a) {
    return new SprintVelocityResponse(
        a.getSprintId(),
        a.getSprintName(),
        a.getCompletedAt(),
        a.getCommittedTasks(),
        a.getCompletedTasks(),
        a.getCommittedPoints(),
        a.getCompletedPoints(),
        a.getSpilloverPoints(),
        a.getSpilloverRate(),
        a.getCalculatedAt());
  }
}
