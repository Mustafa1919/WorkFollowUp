package com.app.tracker.workspace.dto;

import java.util.UUID;

/** {@code GET /api/v1/workspaces}: kullanicinin uye oldugu workspace ve oradaki rolu. */
public record WorkspaceMembershipResponse(UUID id, String name, String planType, String role) {}
