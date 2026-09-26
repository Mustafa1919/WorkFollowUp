package com.app.tracker.core.security;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** ADR-0018 -- PAT'lerin (acik API) hiz siniri, JWT ile giris yapan tarayici trafiginden AYRI. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.pat")
public class PatProperties {

  private long rateLimitRequestsPerWindow = 60;
  private Duration rateLimitWindow = Duration.ofMinutes(1);
}
