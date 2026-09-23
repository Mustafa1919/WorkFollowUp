package com.app.tracker.workspace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Email infrastrukturu yok (davet ucusu bilinen bir sinir, RAKIP_ANALIZI.md): eklenen kullanici
 * ONCEDEN kendi hesabini {@code /api/v1/auth/register} ile actirmis olmalidir. {@code role} 4 sabit
 * degerden biri olmali (WorkspaceRole) — DB'de CHECK yok, dogrulama WorkspaceMemberService'te
 * (TaskService.VALID_STATUSES ile AYNI desen).
 */
public record AddWorkspaceMemberRequest(@NotBlank @Email String email, @NotBlank String role) {}
