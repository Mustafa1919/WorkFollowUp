package com.app.tracker.core.kafka;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record ReplayRequest(
    @NotBlank String dltTopic,
    @Min(0) int partition,
    @PositiveOrZero long fromOffset,
    @PositiveOrZero long toOffset) {}
