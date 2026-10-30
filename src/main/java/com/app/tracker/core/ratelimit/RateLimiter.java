package com.app.tracker.core.ratelimit;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis'te sabit pencere (fixed window) sayac: BruteForceGuard/PatRateLimiter ile ayni INCR + ilk
 * artista EXPIRE deseni, tek Lua betiginde (atomik, tek gidis-donus).
 *
 * <p>Betik INCR'dan sonra TTL'e bakar: TTL yoksa (yeni anahtar ya da INCR ile EXPIRE arasinda
 * sureci olmus eski bir anahtar, TTL -1) pencere yeniden konur; sayac sonsuza kadar kilitli kalmaz.
 *
 * <p>Redis'e erisilemezse istek REDDEDILMEZ (fail-open), WARN yazilir.
 */
@Component
public class RateLimiter {

  private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

  private static final RedisScript<List> SCRIPT =
      new DefaultRedisScript<>(
          "local c = redis.call('INCR', KEYS[1]) "
              + "local t = redis.call('PTTL', KEYS[1]) "
              + "if t < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) t = tonumber(ARGV[1]) end "
              + "return {c, t}",
          List.class);

  private final StringRedisTemplate redisTemplate;

  public RateLimiter(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  /** Sonuc: izin ve (reddedildiyse) pencerede kalan saniye, en az 1. */
  public record Decision(boolean allowed, long count, long retryAfterSeconds) {

    static Decision allow(long count) {
      return new Decision(true, count, 0);
    }
  }

  /** Sayaci bir artirir; {@code limit}'i asan istek reddedilir. */
  public Decision hit(String key, long limit, Duration window) {
    List<?> result;
    try {
      result = redisTemplate.execute(SCRIPT, List.of(key), String.valueOf(window.toMillis()));
    } catch (DataAccessException e) {
      log.warn("Hiz siniri sayaci okunamadi, istek sinirsiz geciriliyor: {}", e.getMessage());
      return Decision.allow(0);
    }
    if (result == null || result.size() < 2) {
      return Decision.allow(0);
    }
    long count = ((Number) result.get(0)).longValue();
    long ttlMillis = ((Number) result.get(1)).longValue();
    if (count <= limit) {
      return Decision.allow(count);
    }
    long retryAfter = Math.max(1, (ttlMillis + 999) / 1000);
    return new Decision(false, count, retryAfter);
  }
}
