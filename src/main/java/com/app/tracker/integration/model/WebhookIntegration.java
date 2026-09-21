package com.app.tracker.integration.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * V13__webhook_integrations.sql — RLS'e tabidir (yonetim endpoint'leri icin). HMAC secret'i
 * SAKLANMAZ; {@code secretVersion}'dan turetilir (bkz. WebhookSecretService).
 */
@Entity
@Table(name = "webhook_integrations")
@Getter
@NoArgsConstructor
public class WebhookIntegration {

  public static final String PROVIDER_GITHUB = "github";

  @Id private UUID id;

  private UUID workspaceId;

  private String provider;

  private int secretVersion;

  private Instant createdAt;

  public static WebhookIntegration github(UUID id, UUID workspaceId) {
    WebhookIntegration integration = new WebhookIntegration();
    integration.id = id;
    integration.workspaceId = workspaceId;
    integration.provider = PROVIDER_GITHUB;
    integration.secretVersion = 1;
    integration.createdAt = Instant.now();
    return integration;
  }

  /**
   * Eski secret ile imzalanmis istekler bu andan itibaren reddedilir (cache TTL'i kadar
   * gecikmeyle).
   */
  public void rotateSecret() {
    this.secretVersion++;
  }
}
