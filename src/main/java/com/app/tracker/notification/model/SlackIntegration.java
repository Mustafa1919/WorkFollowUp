package com.app.tracker.notification.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * V14__slack_integrations.sql — workspace basina tek satir, RLS'e tabidir. Adres YALNIZ sifreli
 * saklanir (bkz. SlackUrlCipher); bu sinif sifresini asla cozmez.
 */
@Entity
@Table(name = "slack_integrations")
@Getter
@NoArgsConstructor
public class SlackIntegration {

  @Id private UUID workspaceId;

  private String webhookUrlEncrypted;

  private boolean enabled;

  private Instant createdAt;

  private Instant updatedAt;

  public static SlackIntegration of(UUID workspaceId, String webhookUrlEncrypted, boolean enabled) {
    SlackIntegration integration = new SlackIntegration();
    integration.workspaceId = workspaceId;
    integration.webhookUrlEncrypted = webhookUrlEncrypted;
    integration.enabled = enabled;
    integration.createdAt = Instant.now();
    integration.updatedAt = integration.createdAt;
    return integration;
  }

  public void update(String webhookUrlEncrypted, boolean enabled) {
    this.webhookUrlEncrypted = webhookUrlEncrypted;
    this.enabled = enabled;
    this.updatedAt = Instant.now();
  }
}
