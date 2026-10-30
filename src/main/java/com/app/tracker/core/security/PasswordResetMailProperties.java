package com.app.tracker.core.security;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Alici basina sifirlama postasi butcesi; HTTP hiz sinirindan bagimsizdir. */
@Getter
@Setter
@ConfigurationProperties("app.auth.password-reset-mail")
public class PasswordResetMailProperties {
  private long limit = 3;
  private Duration window = Duration.ofMinutes(15);
}
