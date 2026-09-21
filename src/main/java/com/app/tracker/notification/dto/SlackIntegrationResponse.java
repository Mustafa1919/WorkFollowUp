package com.app.tracker.notification.dto;

import com.app.tracker.notification.model.SlackIntegration;
import java.time.Instant;

/** Webhook adresini ASLA icermez (kimlik bilgisi): yonetici yalniz durumu gorur. */
public record SlackIntegrationResponse(boolean enabled, Instant createdAt, Instant updatedAt) {

  public static SlackIntegrationResponse from(SlackIntegration integration) {
    return new SlackIntegrationResponse(
        integration.isEnabled(), integration.getCreatedAt(), integration.getUpdatedAt());
  }
}
