package com.app.tracker.workspace.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangeWorkspaceMemberRoleRequest(@NotBlank String role) {}
