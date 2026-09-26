package com.app.tracker.core.security;

import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * ADR-0018 -- token basina Redis'te sabit pencere (fixed window) sayac, BruteForceGuard ile AYNI
 * INCR+EXPIRE deseni (gercek sliding window degil -- hobi projesi olceginde bu fark onemsiz).
 * Anahtar token ID'sine gore, kullaniciya gore DEGIL: bir kullanicinin birden fazla token'i varsa
 * her biri kendi butcesini tasir (birini yeniden tasarim yapmadan tuketmek digerini etkilemez).
 */
@Component
public class PatRateLimiter {

  private final StringRedisTemplate redisTemplate;
  private final PatProperties properties;

  public PatRateLimiter(StringRedisTemplate redisTemplate, PatProperties properties) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
  }

  public record CheckResult(boolean allowed, long limit, long remaining, long retryAfterSeconds) {}

  public CheckResult checkAndIncrement(UUID tokenId) {
    String key = "pat_rate:" + tokenId;
    long limit = properties.getRateLimitRequestsPerWindow();
    Long count = redisTemplate.opsForValue().increment(key);
    if (count != null && count == 1L) {
      redisTemplate.expire(key, properties.getRateLimitWindow());
    }
    long used = count == null ? 1 : count;
    if (used > limit) {
      Long ttl = redisTemplate.getExpire(key);
      long retryAfter = ttl != null && ttl > 0 ? ttl : properties.getRateLimitWindow().toSeconds();
      return new CheckResult(false, limit, 0, retryAfter);
    }
    return new CheckResult(true, limit, limit - used, 0);
  }
}
