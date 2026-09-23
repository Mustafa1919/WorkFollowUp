package com.app.tracker.notification.dto;

import com.app.tracker.notification.model.Notification;
import java.time.Instant;
import java.util.UUID;

/**
 * REST cevabi VE STOMP {@code /user/queue/notifications} push'unda AYNI sekil kullanilir (bkz.
 * InboxNotificationConsumer). {@code payload} kolonu BILEREK YOK: Notification entity'ye hic
 * maplenmedi (Hibernate 7 + Jackson 3 JSONB belirsizligi, bkz. entity javadoc'u), title/body zaten
 * insan-okunur ozeti tasiyor.
 */
public record NotificationResponse(
    UUID id,
    String type,
    UUID taskId,
    UUID projectId,
    String title,
    String body,
    boolean read,
    Instant createdAt) {

  public static NotificationResponse from(Notification notification) {
    return new NotificationResponse(
        notification.getId(),
        notification.getType(),
        notification.getTaskId(),
        notification.getProjectId(),
        notification.getTitle(),
        notification.getBody(),
        !notification.isUnread(),
        notification.getCreatedAt());
  }
}
