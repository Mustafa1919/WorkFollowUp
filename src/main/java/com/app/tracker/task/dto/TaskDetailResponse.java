package com.app.tracker.task.dto;

import com.app.tracker.task.service.TaskService.TaskDetail;
import java.util.List;
import java.util.UUID;

/**
 * Gorev detayi (V22): liste yanitlarinda tasinmayan aciklama + izleyiciler. {@code watching},
 * istegi yapan kullanicinin izleyip izlemedigi (istemci ayrica hesaplamasin).
 */
public record TaskDetailResponse(
    UUID taskId, String description, UUID createdBy, List<UUID> watcherIds, boolean watching) {

  public TaskDetailResponse {
    watcherIds = List.copyOf(watcherIds);
  }

  public static TaskDetailResponse from(TaskDetail detail, UUID currentUserId) {
    return new TaskDetailResponse(
        detail.task().getId(),
        detail.task().getDescription(),
        detail.task().getCreatedBy(),
        detail.watcherIds(),
        detail.watcherIds().contains(currentUserId));
  }
}
