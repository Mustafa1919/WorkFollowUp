package com.app.tracker.task.dto;

import com.app.tracker.task.model.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TaskResponse(
    UUID id,
    UUID projectId,
    UUID sprintId,
    int taskNumber,
    String title,
    String status,
    LocalDate dueDate,
    Instant approvedAt,
    Instant createdAt) {

  public static TaskResponse from(Task task) {
    return new TaskResponse(
        task.getId(),
        task.getProjectId(),
        task.getSprintId(),
        task.getTaskNumber(),
        task.getTitle(),
        task.getStatus(),
        task.getDueDate(),
        task.getApprovedAt(),
        task.getCreatedAt());
  }
}
