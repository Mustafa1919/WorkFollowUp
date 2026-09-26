package com.app.tracker.accesstoken.dto;

import com.app.tracker.accesstoken.service.PersonalAccessTokenService.IssuedToken;

/**
 * Duz metin token SADECE bu yanitta bir defa gorunur -- WebhookIntegration'in "secret" alaniyla
 * AYNI desen (bkz. SettingsPage.tsx WebhookSettings "reveal secret" akisi).
 */
public record AccessTokenCreatedResponse(AccessTokenResponse token, String rawToken) {

  public static AccessTokenCreatedResponse from(IssuedToken issued) {
    return new AccessTokenCreatedResponse(
        AccessTokenResponse.from(issued.entity()), issued.rawToken());
  }
}
