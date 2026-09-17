package com.app.tracker.core.security;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.1 — access 15dk, refresh 7 gun, RS256. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

  private String privateKeyLocation;
  private String publicKeyLocation;
  private Duration accessTokenTtl = Duration.ofMinutes(15);
  private Duration refreshTokenTtl = Duration.ofDays(7);
}
