package com.app.tracker.goal.dto;

import com.app.tracker.goal.model.GoalMetricType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Hedef olusturma govdesi. Donem ({@code year}/{@code quarter}) ve {@code metricType} yalniz
 * OLUSTURMADA verilir; ikisi de sonradan degistirilemez (bkz. {@code Goal.edit}).
 *
 * @param quarter {@code null} = yillik hedef
 * @param projectId {@code null} = workspace geneli hedef
 */
public record GoalRequest(
    @NotBlank @Size(max = 200) String title,
    @NotNull GoalMetricType metricType,
    @Positive long targetValue,
    @Min(2000) @Max(2999) int year,
    @Min(1) @Max(4) Integer quarter,
    UUID projectId) {}
