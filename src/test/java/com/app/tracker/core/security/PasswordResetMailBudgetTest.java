package com.app.tracker.core.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.tracker.core.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasswordResetMailBudgetTest {
  @Test
  void delegatesAccountKeyAndDefaultBudgetToAtomicCounter() {
    RateLimiter limiter = mock(RateLimiter.class);
    UUID userId = UUID.randomUUID();
    String key = "pwreset_mail:" + userId;
    when(limiter.hit(key, 3, Duration.ofMinutes(15)))
        .thenReturn(new RateLimiter.Decision(true, 3, 0));
    PasswordResetMailBudget budget =
        new PasswordResetMailBudget(limiter, new PasswordResetMailProperties());

    assertTrue(budget.tryAcquire(userId));
    verify(limiter).hit(key, 3, Duration.ofMinutes(15));
  }

  @Test
  void usesConfiguredBudgetAndReturnsCounterDecision() {
    RateLimiter limiter = mock(RateLimiter.class);
    PasswordResetMailProperties properties = new PasswordResetMailProperties();
    properties.setLimit(7);
    properties.setWindow(Duration.ofSeconds(2));
    UUID userId = UUID.randomUUID();
    when(limiter.hit("pwreset_mail:" + userId, 7, Duration.ofSeconds(2)))
        .thenReturn(new RateLimiter.Decision(false, 8, 1));

    assertFalse(new PasswordResetMailBudget(limiter, properties).tryAcquire(userId));
  }
}
