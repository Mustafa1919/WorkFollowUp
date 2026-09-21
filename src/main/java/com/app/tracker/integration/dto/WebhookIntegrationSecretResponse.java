package com.app.tracker.integration.dto;

import com.app.tracker.integration.service.WebhookIntegrationService.IntegrationWithSecret;

/**
 * Olusturma/rotasyon yaniti: GitHub webhook ayarina girilecek {@code secret} burada doner. Secret
 * turetildigi icin tekrar gosterilebilir olsa da API bunu bilerek YALNIZ bu iki islemde verir.
 */
public record WebhookIntegrationSecretResponse(
    WebhookIntegrationResponse integration, String secret) {

  public static WebhookIntegrationSecretResponse from(IntegrationWithSecret created) {
    return new WebhookIntegrationSecretResponse(
        WebhookIntegrationResponse.from(created.integration()), created.secret());
  }
}
