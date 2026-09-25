package com.app.tracker.workspace.dto;

import java.util.UUID;

/** Kabul basarili olunca frontend dogrudan bu workspace'e gecebilsin diye. */
public record AcceptInvitationResponse(UUID workspaceId, String workspaceName, String role) {}
