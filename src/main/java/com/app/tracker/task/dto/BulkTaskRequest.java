package com.app.tracker.task.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code sprintId}/{@code assigneeId}: {@code null} SPRINT/ASSIGNEE icin gecerli bir degerdir
 * (backlog'a alma / atamayi kaldirma — TaskService.assignSprint/assign ile AYNI anlam), bu yuzden
 * ayri bir "temizle" bayragi gerekmez. {@code status}/{@code tagId} operasyona gore zorunlu, servis
 * katmaninda dogrulanir (DTO seviyesinde zorunlu kilinmaz, cunku operasyona gore degisir).
 */
public record BulkTaskRequest(
    @NotEmpty @Size(max = 100) List<UUID> taskIds,
    @NotNull BulkOperation operation,
    String status,
    UUID sprintId,
    UUID assigneeId,
    UUID tagId) {

  public BulkTaskRequest {
    taskIds = taskIds == null ? null : List.copyOf(taskIds);
  }
}
