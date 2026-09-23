package com.app.tracker.notification.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * V18__notifications.sql — bir kullanicinin gelen kutusundaki tek bildirim. RLS'e tabidir
 * (workspace izolasyonu); kullanicilar-arasi izolasyon (yalniz kendi bildirimin) servis katmaninda
 * {@code user_id} filtresiyle saglanir (bkz. migration javadoc'u).
 *
 * <p>{@code payload} kolonu BILEREK entity'ye MAPLENMEDI (Task#custom_fields ile ayni gerekce:
 * Hibernate 7 + Jackson 3 JSONB mapping belirsizligi); yalniz {@link
 * com.app.tracker.notification.repository.NotificationRepository#writePayload} native SQL ile
 * yazar, REST'e hic yansitilmaz.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor
public class Notification {

  @Id private UUID id;

  private UUID workspaceId;

  private UUID userId;

  private String type;

  private UUID taskId;

  /** V18 notu: verilen kolon listesinin disinda bilerek eklendi (bkz. migration javadoc'u). */
  private UUID projectId;

  private String title;

  private String body;

  private Instant readAt;

  private Instant createdAt;

  public static Notification of(
      UUID id,
      UUID workspaceId,
      UUID userId,
      String type,
      UUID taskId,
      UUID projectId,
      String title,
      String body,
      Instant createdAt) {
    Notification notification = new Notification();
    notification.id = id;
    notification.workspaceId = workspaceId;
    notification.userId = userId;
    notification.type = type;
    notification.taskId = taskId;
    notification.projectId = projectId;
    notification.title = title;
    notification.body = body;
    notification.createdAt = createdAt;
    return notification;
  }

  public boolean isUnread() {
    return readAt == null;
  }

  public void markRead(Instant at) {
    this.readAt = at;
  }
}
