package com.app.tracker.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** {@code storyPoint = null}: tahmini kaldirir. Ust sinir (100) anlamsiz degerleri eler. */
public record UpdateStoryPointRequest(@Min(0) @Max(100) Integer storyPoint) {}
