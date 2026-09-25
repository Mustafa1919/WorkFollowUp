package com.app.tracker.notification.preferences.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * V24__notification_preferences.sql — kullaniciya ait (workspace'e degil), RLS'e tabi DEGIL (bkz.
 * migration yorumu, auth tablolariyla ayni mantik). Satir yoksa varsayilan davranis (ikisi de acik)
 * {@link com.app.tracker.notification.preferences.service.NotificationPreferencesService}
 * katmaninda uygulanir; bu tablo yalniz varsayilandan sapan kullanicilar icin satir tutar.
 */
@Entity
@Table(name = "notification_preferences")
@Getter
@NoArgsConstructor
public class NotificationPreferences {

  @Id private UUID userId;

  private boolean emailOnAssign;

  private boolean emailOnMention;

  private Instant updatedAt;

  public static NotificationPreferences of(
      UUID userId, boolean emailOnAssign, boolean emailOnMention) {
    NotificationPreferences prefs = new NotificationPreferences();
    prefs.userId = userId;
    prefs.emailOnAssign = emailOnAssign;
    prefs.emailOnMention = emailOnMention;
    prefs.updatedAt = Instant.now();
    return prefs;
  }

  public void update(boolean emailOnAssign, boolean emailOnMention) {
    this.emailOnAssign = emailOnAssign;
    this.emailOnMention = emailOnMention;
    this.updatedAt = Instant.now();
  }
}
