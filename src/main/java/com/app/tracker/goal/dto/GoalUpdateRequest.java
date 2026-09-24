package com.app.tracker.goal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Degisebilen alanlar; donem ve metrik tipi bilerek YOK (hedefin kimligi). */
public record GoalUpdateRequest(
    @NotBlank @Size(max = 200) String title, @Positive long targetValue, UUID projectId) {}
