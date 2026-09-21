package com.app.tracker.integration;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 2 — webhook ingestion ayarlari.
 *
 * <p>{@code maxBodySize}: govde Kafka mesajinin icine (envelope'a) gomuldugu icin Kafka'nin 1 MB
 * varsayilan mesaj sinirinin ALTINDA tutulur; asan istek 413 alir (GitHub'in kendi tavani 25 MB,
 * cok buyuk push'lar bu sinira takilabilir — bilinen, bilincli sinir).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.webhook")
public class WebhookProperties {

  /** Repodaki gelistirme anahtarinin on eki (bkz. WebhookSecretService guard'i). */
  public static final String DEV_KEY_MARKER = "dev-webhook-";

  private String secretMasterKey;
  private DataSize maxBodySize = DataSize.ofKilobytes(512);
  private Duration integrationCacheTtl = Duration.ofSeconds(60);
  private Duration sendTimeout = Duration.ofSeconds(2);
}
