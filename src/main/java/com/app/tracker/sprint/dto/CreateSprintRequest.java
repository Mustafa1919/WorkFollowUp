package com.app.tracker.sprint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateSprintRequest(
    @NotBlank @Size(max = 100) String name,
    String goal,
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate) {}
