package com.app.tracker.core.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.4.1 — Redis'te iki katmanli sayac. Basitlestirme:
 * gercek bir "sliding window" degil, INCR+EXPIRE ile sabit pencere (fixed window) kullanilir — hobi
 * projesi olcegi icin bu fark pratikte onemsizdir, ama tam sliding log daha dogru olurdu. IP'ye 50
 * basarisizlikta gecici blok, Gateway seviyesinde uygulanacagi icin (Faz4, henuz yok) burada YOK;
 * sadece 20 esiginde captcha bayragi doner.
 */
@Component
public class BruteForceGuard {

  private static final Duration WINDOW = Duration.ofMinutes(15);
  private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
  private static final long USER_LOCK_THRESHOLD = 10;
  private static final long IP_CAPTCHA_THRESHOLD = 20;
  private static final long MAX_BACKOFF_SECONDS = 60;

  private final StringRedisTemplate redisTemplate;

  public BruteForceGuard(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  public record CheckResult(boolean locked, boolean throttled, boolean captchaRequired) {}

  public CheckResult checkBeforeAttempt(String email, String clientIp) {
    boolean locked = Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(email)));
    boolean captchaRequired = countOf(ipKey(clientIp)) >= IP_CAPTCHA_THRESHOLD;
    boolean throttled = !locked && isThrottled(email);
    return new CheckResult(locked, throttled, captchaRequired);
  }

  public void recordFailure(String email, String clientIp) {
    long userFailCount = increment(userKey(email));
    redisTemplate
        .opsForValue()
        .set(lastFailKey(email), String.valueOf(Instant.now().toEpochMilli()), WINDOW);
    increment(ipKey(clientIp));
    if (userFailCount >= USER_LOCK_THRESHOLD) {
      redisTemplate.opsForValue().set(lockKey(email), "1", LOCK_DURATION);
      // TODO Faz2/3: notification.email Kafka olayi ("hesabiniz gecici olarak kilitlendi").
    }
  }

  public void recordSuccess(String email) {
    redisTemplate.delete(userKey(email));
    redisTemplate.delete(lastFailKey(email));
    redisTemplate.delete(lockKey(email));
  }

  private boolean isThrottled(String email) {
    long count = countOf(userKey(email));
    if (count <= 1) {
      return false;
    }
    long delaySeconds = Math.min(1L << Math.min(count - 1, 6), MAX_BACKOFF_SECONDS);
    String lastFailValue = redisTemplate.opsForValue().get(lastFailKey(email));
    if (lastFailValue == null) {
      return false;
    }
    long lastFailMillis = Long.parseLong(lastFailValue);
    return Instant.now().toEpochMilli()
        < lastFailMillis + Duration.ofSeconds(delaySeconds).toMillis();
  }

  private long increment(String key) {
    Long value = redisTemplate.opsForValue().increment(key);
    if (value != null && value == 1L) {
      redisTemplate.expire(key, WINDOW);
    }
    return value == null ? 0 : value;
  }

  private long countOf(String key) {
    String value = redisTemplate.opsForValue().get(key);
    return value == null ? 0 : Long.parseLong(value);
  }

  private static String userKey(String email) {
    return "login_fail:user:" + email;
  }

  private static String lastFailKey(String email) {
    return "login_fail:user:" + email + ":last";
  }

  private static String ipKey(String clientIp) {
    return "login_fail:ip:" + clientIp;
  }

  private static String lockKey(String email) {
    return "login_lock:user:" + email;
  }
}
