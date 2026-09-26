package com.app.tracker.accesstoken.dto;

import com.app.tracker.accesstoken.model.PersonalAccessToken;
import java.time.Instant;
import java.util.UUID;

public record AccessTokenResponse(
    UUID id,
    String name,
    String tokenPreview,
    Instant lastUsedAt,
    Instant expiresAt,
    Instant createdAt,
    boolean revoked) {

  public static AccessTokenResponse from(PersonalAccessToken token) {
    return new AccessTokenResponse(
        token.getId(),
        token.getName(),
        token.getTokenPreview(),
        token.getLastUsedAt(),
        token.getExpiresAt(),
        token.getCreatedAt(),
        token.getRevokedAt() != null);
  }
}
