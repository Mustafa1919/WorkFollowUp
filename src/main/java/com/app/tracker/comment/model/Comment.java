package com.app.tracker.comment.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * V23 {@code comments} — tek seviye (thread yok), RLS'e tabidir (V6 fail-closed politikasi).
 * Silinen yorumun {@code body}'si DB'de KORUNUR ({@code deletedAt} dolu olur); "[silindi]" maskesi
 * yalniz API katmaninda (CommentResponse#from) uygulanir, boylece ileride bir denetim ihtiyaci
 * cikarsa asil metin kaybolmaz.
 */
@Entity
@Table(name = "comments")
@Getter
@NoArgsConstructor
public class Comment {

  @Id private UUID id;

  private UUID workspaceId;

  private UUID taskId;

  private UUID authorId;

  private String body;

  private Instant createdAt;

  private Instant updatedAt;

  private Instant deletedAt;

  public static Comment of(
      UUID id, UUID workspaceId, UUID taskId, UUID authorId, String body, Instant now) {
    Comment comment = new Comment();
    comment.id = id;
    comment.workspaceId = workspaceId;
    comment.taskId = taskId;
    comment.authorId = authorId;
    comment.body = body;
    comment.createdAt = now;
    comment.updatedAt = now;
    return comment;
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  public boolean isEdited() {
    return !isDeleted() && !createdAt.equals(updatedAt);
  }

  public void editBody(String newBody, Instant now) {
    this.body = newBody;
    this.updatedAt = now;
  }

  public void softDelete(Instant now) {
    this.deletedAt = now;
    this.updatedAt = now;
  }
}
