package com.app.tracker.workspace.dto;

/**
 * {@code GET /api/v1/invitations/{token}} — public (permitAll), token sahibi henuz giris yapmamis
 * olabilir. {@code status}: PENDING/ACCEPTED/REVOKED/EXPIRED (sonuncusu DB'de yok, turetilir).
 */
public record InvitationPreviewResponse(
    String workspaceName, String email, String role, String status) {}
