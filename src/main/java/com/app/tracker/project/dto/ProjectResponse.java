package com.app.tracker.project.dto;

import com.app.tracker.project.model.Project;
import java.util.UUID;

public record ProjectResponse(UUID id, UUID workspaceId, String key, String name) {

  public static ProjectResponse from(Project project) {
    return new ProjectResponse(
        project.getId(), project.getWorkspaceId(), project.getKey(), project.getName());
  }
}
