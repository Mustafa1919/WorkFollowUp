package com.app.tracker.core.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Sabit pencere betiginin gercek Redis'teki davranisi + Redis yokken fail-open. */
class RateLimiterTest {

  private static GenericContainer<?> redis;
  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate template;

  @BeforeAll
  static void start() {
    redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    redis.start();
    factory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
    factory.afterPropertiesSet();
    factory.start();
    template = new StringRedisTemplate(factory);
  }

  @AfterAll
  static void stop() {
    factory.destroy();
    redis.stop();
  }

  private static String key() {
    return "rate:test:ip:" + UUID.randomUUID();
  }

  @Test
  void allowsUpToLimit_thenDeniesWithRetryAfter() {
    RateLimiter limiter = new RateLimiter(template);
    String key = key();
    for (int i = 1; i <= 3; i++) {
      RateLimiter.Decision d = limiter.hit(key, 3, Duration.ofMinutes(1));
      assertTrue(d.allowed(), "istek " + i);
      assertEquals(i, d.count());
    }
    RateLimiter.Decision over = limiter.hit(key, 3, Duration.ofMinutes(1));
    assertFalse(over.allowed());
    assertTrue(over.retryAfterSeconds() >= 1 && over.retryAfterSeconds() <= 60, "" + over);
    Long ttl = template.getExpire(key);
    assertTrue(ttl != null && ttl > 0 && ttl <= 60, "ttl " + ttl);
  }

  @Test
  void keyWithoutTtl_getsWindowReapplied() {
    RateLimiter limiter = new RateLimiter(template);
    String key = key();
    template.opsForValue().set(key, "500");
    assertEquals(-1L, template.getExpire(key));
    RateLimiter.Decision d = limiter.hit(key, 3, Duration.ofSeconds(30));
    assertFalse(d.allowed());
    Long ttl = template.getExpire(key);
    assertTrue(ttl != null && ttl > 0 && ttl <= 30, "ttl " + ttl);
    assertTrue(d.retryAfterSeconds() >= 1 && d.retryAfterSeconds() <= 30);
  }

  @Test
  void windowExpiry_resetsCounter() throws InterruptedException {
    RateLimiter limiter = new RateLimiter(template);
    String key = key();
    assertTrue(limiter.hit(key, 1, Duration.ofMillis(300)).allowed());
    assertFalse(limiter.hit(key, 1, Duration.ofMillis(300)).allowed());
    Thread.sleep(600);
    assertTrue(limiter.hit(key, 1, Duration.ofMillis(300)).allowed());
  }

  @Test
  void redisUnavailable_failsOpen() {
    LettuceConnectionFactory dead =
        new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
    dead.afterPropertiesSet();
    dead.start();
    try {
      RateLimiter limiter = new RateLimiter(new StringRedisTemplate(dead));
      RateLimiter.Decision d = limiter.hit(key(), 1, Duration.ofMinutes(1));
      assertTrue(d.allowed());
    } finally {
      dead.destroy();
    }
  }
}
