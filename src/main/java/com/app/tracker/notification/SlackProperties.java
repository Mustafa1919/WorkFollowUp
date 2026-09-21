package com.app.tracker.notification;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 3 — Slack bildirimi ayarlari. Zaman asimlari ve devre kesici
 * esikleri; Slack tum tenant'lar icin AYNI dis bagimlilik oldugundan devre kesici tektir (tenant
 * basina degil).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.notification.slack")
public class SlackProperties {

  private Duration connectTimeout = Duration.ofSeconds(2);
  private Duration readTimeout = Duration.ofSeconds(5);

  private int slidingWindowSize = 20;
  private int minimumNumberOfCalls = 5;
  private float failureRateThreshold = 50f;
  private Duration openStateDuration = Duration.ofSeconds(30);
  private int permittedCallsInHalfOpenState = 3;
}
