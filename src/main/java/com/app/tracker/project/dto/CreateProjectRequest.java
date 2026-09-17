package com.app.tracker.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
    @NotBlank @Size(max = 10) String key, @NotBlank @Size(max = 100) String name) {}
