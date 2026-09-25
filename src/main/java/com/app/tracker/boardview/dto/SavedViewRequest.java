package com.app.tracker.boardview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SavedViewRequest(
    @NotBlank @Size(max = 60) String name, @NotBlank @Size(max = 4000) String query) {}
