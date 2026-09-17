package com.app.tracker.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(@NotBlank @Size(max = 100) String name) {}
