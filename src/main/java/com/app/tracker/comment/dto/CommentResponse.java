package com.app.tracker.comment.dto;

import com.app.tracker.comment.model.Comment;
import java.time.Instant;
import java.util.UUID;

/**
 * Silinmis yorumun govdesi DB'de korunur ama burada MASKELENIR ("[silindi]") — bkz. Comment
 * javadoc'u.
 */
public record CommentResponse(
    UUID id,
    UUID taskId,
    UUID authorId,
    String body,
    boolean edited,
    boolean deleted,
    Instant createdAt,
    Instant updatedAt) {

  public static CommentResponse from(Comment comment) {
    boolean deleted = comment.isDeleted();
    return new CommentResponse(
        comment.getId(),
        comment.getTaskId(),
        comment.getAuthorId(),
        deleted ? "[silindi]" : comment.getBody(),
        comment.isEdited(),
        deleted,
        comment.getCreatedAt(),
        comment.getUpdatedAt());
  }
}
