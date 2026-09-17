package com.app.tracker.core.security;

import java.util.List;
import java.util.UUID;

public record DecodedJwt(UUID userId, List<String> roles, String jti) {

  public DecodedJwt(UUID userId, List<String> roles, String jti) {
    this.userId = userId;
    this.roles = List.copyOf(roles);
    this.jti = jti;
  }
}
