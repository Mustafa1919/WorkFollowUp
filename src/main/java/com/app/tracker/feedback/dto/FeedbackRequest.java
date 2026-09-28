package com.app.tracker.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(
    @NotBlank @Size(max = 4000) String message, @Size(max = 255) String pagePath) {}
