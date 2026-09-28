package com.app.tracker.feedback.dto;

import com.app.tracker.feedback.model.Feedback;
import java.time.Instant;
import java.util.UUID;

public record FeedbackResponse(UUID id, String message, String pagePath, Instant createdAt) {

  public static FeedbackResponse from(Feedback feedback) {
    return new FeedbackResponse(
        feedback.getId(), feedback.getMessage(), feedback.getPagePath(), feedback.getCreatedAt());
  }
}
