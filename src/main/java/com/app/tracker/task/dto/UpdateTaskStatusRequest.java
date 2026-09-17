package com.app.tracker.task.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * PHASE_1_DETAILED_DESIGN.md Bolum 4 — PATCH sadece durum degistirir, PUT (tum kaynagi ezme)
 * kullanilmaz.
 */
public record UpdateTaskStatusRequest(@NotBlank String status) {}
