package com.app.tracker.sprint.dto;

import com.app.tracker.sprint.model.Sprint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SprintResponse(
    UUID id,
    UUID projectId,
    String name,
    String goal,
    String status,
    LocalDate startDate,
    LocalDate endDate,
    Instant startedAt,
    Instant completedAt) {

  public static SprintResponse from(Sprint sprint) {
    return new SprintResponse(
        sprint.getId(),
        sprint.getProjectId(),
        sprint.getName(),
        sprint.getGoal(),
        sprint.getStatus(),
        sprint.getStartDate(),
        sprint.getEndDate(),
        sprint.getStartedAt(),
        sprint.getCompletedAt());
  }
}
