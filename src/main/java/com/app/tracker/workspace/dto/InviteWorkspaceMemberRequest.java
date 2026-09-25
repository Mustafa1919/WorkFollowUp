package com.app.tracker.workspace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code role} 4 sabit degerden biri olmali (WorkspaceRole) — dogrulama
 * WorkspaceInvitationService'te.
 */
public record InviteWorkspaceMemberRequest(@NotBlank @Email String email, @NotBlank String role) {}
