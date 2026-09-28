package com.app.tracker.telemetry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Frontend'in bir hareket penceresinde biriktirip toplu gonderdigi tek kullanim olayi. */
public record FeatureUsageEvent(
    @NotBlank @Size(max = 60) String feature, @NotBlank @Size(max = 60) String action) {}
