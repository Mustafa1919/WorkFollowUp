package com.app.tracker.core.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.1.2 — izinli origin listesi, wildcard yasak. */
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

  private List<String> allowedOrigins = new ArrayList<>();

  public List<String> getAllowedOrigins() {
    return List.copyOf(allowedOrigins);
  }

  public void setAllowedOrigins(List<String> allowedOrigins) {
    this.allowedOrigins = new ArrayList<>(allowedOrigins);
  }
}
