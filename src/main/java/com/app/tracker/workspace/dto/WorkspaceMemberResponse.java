package com.app.tracker.workspace.dto;

import java.util.UUID;

/** {@code GET/POST/PATCH /api/v1/workspaces/members}: workspace uyesi + kimlik bilgisi. */
public record WorkspaceMemberResponse(UUID userId, String email, String fullName, String role) {}
