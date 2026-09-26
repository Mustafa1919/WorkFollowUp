package com.app.tracker.timemachine.dto;

import com.app.tracker.timemachine.TaskSnapshotRepository.Row;
import java.time.LocalDate;
import java.util.UUID;

/**
 * ADR-0017 — board anlik goruntusundeki tek gorev satiri. {@code TaskResponse}'un aksine tags/
 * dependency/comment tasimaz (bilinen sinir, {@code TaskSnapshotRepository} javadoc'una bkz.).
 */
public record TaskSnapshotResponse(
    UUID id,
    int taskNumber,
    String title,
    String status,
    UUID sprintId,
    Integer storyPoint,
    UUID assigneeId,
    LocalDate dueDate) {

  public static TaskSnapshotResponse from(Row row) {
    return new TaskSnapshotResponse(
        row.id(),
        row.taskNumber(),
        row.title(),
        row.status(),
        row.sprintId(),
        row.storyPoint(),
        row.assigneeId(),
        row.dueDate());
  }
}
