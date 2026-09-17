package com.app.tracker.workspace.dto;

import com.app.tracker.workspace.model.Workspace;
import java.util.UUID;

public record WorkspaceResponse(UUID id, String name, String planType) {

  public static WorkspaceResponse from(Workspace workspace) {
    return new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getPlanType());
  }
}
