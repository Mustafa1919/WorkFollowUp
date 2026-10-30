package com.app.tracker.core.ratelimit;

import java.time.Duration;
import java.util.List;

/**
 * Hiz siniri kurali: tam yol + yontem eslesmesi, anahtar turu ve sinir/pencere. Varsayilan tablo
 * {@link #DEFAULTS}; sinir/pencere {@code app.rate-limit.rules.<ad>} ile ezilebilir (SEC-07).
 */
public record RateLimitRule(
    String name, String method, String path, KeyType keyType, long limit, Duration window) {

  /** Sayacin kime gore tutuldugu. */
  public enum KeyType {
    /** Cozulmus istemci IP'si (bkz. ClientIpResolver); kimliksiz uclar. */
    IP,
    /** Kimlikli kullanicinin UUID'si (JWT ve PAT ayni); kimlik yoksa kural uygulanmaz. */
    USER;

    String keyPart() {
      return this == IP ? "ip" : "user";
    }
  }

  public static final List<RateLimitRule> DEFAULTS =
      List.of(
          new RateLimitRule(
              "login", "POST", "/api/v1/auth/login", KeyType.IP, 20, Duration.ofMinutes(1)),
          new RateLimitRule(
              "register", "POST", "/api/v1/auth/register", KeyType.IP, 10, Duration.ofMinutes(1)),
          new RateLimitRule(
              "password-reset",
              "POST",
              "/api/v1/auth/password-reset/request",
              KeyType.IP,
              10,
              Duration.ofMinutes(1)),
          new RateLimitRule(
              "invitation",
              "POST",
              "/api/v1/workspaces/members/invitations",
              KeyType.USER,
              30,
              Duration.ofHours(1)),
          new RateLimitRule(
              "telemetry-usage",
              "POST",
              "/api/v1/telemetry/usage",
              KeyType.USER,
              30,
              Duration.ofMinutes(1)),
          new RateLimitRule(
              "telemetry-error",
              "POST",
              "/api/v1/telemetry/errors",
              KeyType.USER,
              30,
              Duration.ofMinutes(1)),
          new RateLimitRule(
              "feedback", "POST", "/api/v1/feedback", KeyType.USER, 5, Duration.ofMinutes(1)));

  /** Ayardaki ezmelerle (null olmayan alanlar) yeni kural. */
  RateLimitRule withOverride(RateLimitProperties.RuleSettings settings) {
    if (settings == null) {
      return this;
    }
    long effectiveLimit = settings.getLimit() != null ? settings.getLimit() : limit;
    Duration effectiveWindow = settings.getWindow() != null ? settings.getWindow() : window;
    return new RateLimitRule(name, method, path, keyType, effectiveLimit, effectiveWindow);
  }
}
