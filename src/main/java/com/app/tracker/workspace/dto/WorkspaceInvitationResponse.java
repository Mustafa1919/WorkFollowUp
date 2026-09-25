package com.app.tracker.workspace.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * ADMIN'in workspace'teki davetleri listelemesi icin — token ASLA doner (yalniz e-postada gider).
 */
public record WorkspaceInvitationResponse(
    UUID id, String email, String role, String status, Instant expiresAt, Instant createdAt) {}
