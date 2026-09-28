package com.app.tracker.feedback.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Workspace uyesinin uygulama ici gonderdigi serbest metin geri bildirim (V33). RLS'e tabidir. */
@Entity
@Table(name = "feedback")
@Getter
@NoArgsConstructor
public class Feedback {

  @Id private UUID id;

  private UUID workspaceId;

  private UUID userId;

  private String message;

  private String pagePath;

  private Instant createdAt;

  public static Feedback of(
      UUID id, UUID workspaceId, UUID userId, String message, String pagePath) {
    Feedback feedback = new Feedback();
    feedback.id = id;
    feedback.workspaceId = workspaceId;
    feedback.userId = userId;
    feedback.message = message;
    feedback.pagePath = pagePath;
    feedback.createdAt = Instant.now();
    return feedback;
  }
}
