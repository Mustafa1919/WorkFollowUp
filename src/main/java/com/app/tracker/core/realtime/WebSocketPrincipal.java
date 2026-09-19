package com.app.tracker.core.realtime;

import java.security.Principal;
import java.util.UUID;

/**
 * {@code WebSocketAuthInterceptor}'in CONNECT'te STOMP oturumuna baglar (bkz. sinifin javadoc'u).
 */
public record WebSocketPrincipal(UUID userId) implements Principal {

  @Override
  public String getName() {
    return userId.toString();
  }
}
