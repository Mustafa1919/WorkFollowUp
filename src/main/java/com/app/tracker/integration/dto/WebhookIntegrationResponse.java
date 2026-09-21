package com.app.tracker.integration.dto;

import com.app.tracker.integration.model.WebhookIntegration;
import java.time.Instant;
import java.util.UUID;

/** Secret ICERMEZ (listeleme). Secret icin bkz. {@link WebhookIntegrationSecretResponse}. */
public record WebhookIntegrationResponse(
    UUID id, String provider, int secretVersion, Instant createdAt, String webhookPath) {

  public static WebhookIntegrationResponse from(WebhookIntegration integration) {
    return new WebhookIntegrationResponse(
        integration.getId(),
        integration.getProvider(),
        integration.getSecretVersion(),
        integration.getCreatedAt(),
        "/api/v1/webhooks/github/" + integration.getId());
  }
}
