package com.app.tracker.core.security;

import com.app.tracker.core.ratelimit.RateLimiter;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Ham e-posta tutmadan, atomik Redis sayaciyla kayitli hesabin posta butcesini uygular. */
@Component
public class PasswordResetMailBudget {
  private final RateLimiter rateLimiter;
  private final PasswordResetMailProperties properties;

  public PasswordResetMailBudget(RateLimiter rateLimiter, PasswordResetMailProperties properties) {
    this.rateLimiter = rateLimiter;
    this.properties = properties;
  }

  public boolean tryAcquire(UUID userId) {
    return rateLimiter
        .hit("pwreset_mail:" + userId, properties.getLimit(), properties.getWindow())
        .allowed();
  }
}
