package com.app.tracker.notification.email;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dilim 1.3 — e-posta gonderim ayarlari. {@code publicUrl} e-postalardaki linkler icin (dogrulama/
 * sifirlama/gorev) gereklidir; {@code AppPublicUrlGuard} staging/prod'da bos veya localhost iceren
 * bir deger ile acilisi reddeder (JWT/webhook guard'iyla AYNI fail-closed desen).
 *
 * <p>Devre kesici esikleri Slack'teki {@code SlackProperties} ile AYNI yapida — SMTP de tum
 * kullanicilar icin PAYLASILAN tek dis bagimliliktir.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.notification.email")
public class EmailProperties {

  /**
   * DEV-ONLY varsayilan (bkz. AppPublicUrlGuard) — staging/prod gercek alan adiyla override eder.
   */
  private String publicUrl = "http://localhost:5173";

  private String from = "no-reply@tracker.local";

  private Duration connectTimeout = Duration.ofSeconds(5);
  private Duration readTimeout = Duration.ofSeconds(10);

  private int slidingWindowSize = 20;
  private int minimumNumberOfCalls = 5;
  private float failureRateThreshold = 50f;
  private Duration openStateDuration = Duration.ofSeconds(30);
  private int permittedCallsInHalfOpenState = 3;
}
