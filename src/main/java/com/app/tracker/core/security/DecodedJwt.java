package com.app.tracker.core.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code expiresAt}: token'in {@code exp} talebi; acik WebSocket oturumlarinin teslim anindaki sure
 * kontrolu buna dayanir (bkz. {@code WebSocketDeliveryGuard}).
 */
public record DecodedJwt(UUID userId, List<String> roles, String jti, Instant expiresAt) {

  public DecodedJwt(UUID userId, List<String> roles, String jti, Instant expiresAt) {
    this.userId = userId;
    this.roles = List.copyOf(roles);
    this.jti = jti;
    this.expiresAt = expiresAt;
  }
}
