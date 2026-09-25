package com.app.tracker.boardview.dto;

import com.app.tracker.boardview.model.SavedView;
import java.time.Instant;
import java.util.UUID;

public record SavedViewResponse(
    UUID id, UUID projectId, String name, String query, Instant createdAt) {

  public static SavedViewResponse from(SavedView view) {
    return new SavedViewResponse(
        view.getId(), view.getProjectId(), view.getName(), view.getQuery(), view.getCreatedAt());
  }
}
