package com.app.tracker.core.security;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** JwtAuthenticationFilter, principal'i userId (UUID) olarak koyar (bkz. o sinif). */
public final class CurrentUser {

  private CurrentUser() {}

  public static UUID id() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new AccessDeniedException("Kimlik dogrulanmamis istek.");
    }
    return (UUID) authentication.getPrincipal();
  }
}
