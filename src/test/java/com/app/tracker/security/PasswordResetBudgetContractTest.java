package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.notification.EmailNotificationPublisher;
import com.app.tracker.core.ratelimit.RateLimiter;
import com.app.tracker.core.security.*;
import com.app.tracker.core.security.dto.PasswordResetRequestDto;
import com.app.tracker.core.security.repository.RefreshTokenRepository;
import com.app.tracker.core.security.repository.VerificationTokenRepository;
import com.app.tracker.core.web.ClientIpResolver;
import com.app.tracker.security.support.MailpitInbox;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.NestedTestConfiguration;
import tools.jackson.databind.ObjectMapper;

/** Dilim 05: beklentiler sozlesmeden; HTTP/SMTP gozlemleri gercek altyapidadir. */
@NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
class PasswordResetBudgetContractTest {
  private static final AtomicInteger IPS = new AtomicInteger(1);

  @Test
  void R1_K1_twentyAttemptsProduceExactlyThreeTokensAndMailEvents() {
    Unit h = new Unit();
    AtomicInteger count = new AtomicInteger();
    doAnswer(invocation -> List.of((long) count.incrementAndGet(), 900_000L))
        .when(h.redis)
        .execute(any(RedisScript.class), anyList(), any(Object[].class));
    for (int i = 0; i < 20; i++) h.service.requestPasswordReset(h.user.getEmail());
    verify(h.tokens, times(3)).save(any());
    verify(h.mail, times(3))
        .publishVerificationEmail(eq(h.user.getId()), eq("PASSWORD_RESET"), anyString());
    verify(h.redis, times(20))
        .execute(
            any(RedisScript.class), eq(List.of("pwreset_mail:" + h.user.getId())), eq("900000"));
  }

  @Test
  void R3_unknownAccountDoesNotTouchRedisButKnownAccountConsumesUuidBudget() {
    Unit h = new Unit();
    h.service.requestPasswordReset("absent@tracker.local");
    verifyNoInteractions(h.redis, h.tokens, h.mail);
    doReturn(List.of(1L, 900_000L))
        .when(h.redis)
        .execute(any(RedisScript.class), anyList(), any(Object[].class));
    h.service.requestPasswordReset(h.user.getEmail());
    verify(h.redis)
        .execute(
            any(RedisScript.class), eq(List.of("pwreset_mail:" + h.user.getId())), eq("900000"));
    verify(h.tokens).save(any());
    verify(h.mail).publishVerificationEmail(eq(h.user.getId()), eq("PASSWORD_RESET"), anyString());
  }

  @Test
  void R4_redisFailureStillReturns202IssuesTokenAndMailAndLogsWarn() {
    Unit h = new Unit();
    doThrow(new DataAccessResourceFailureException("contract redis offline"))
        .when(h.redis)
        .execute(any(RedisScript.class), anyList(), any(Object[].class));
    Logger logger = (Logger) LoggerFactory.getLogger(RateLimiter.class);
    ListAppender<ILoggingEvent> events = new ListAppender<>();
    events.start();
    logger.addAppender(events);
    try {
      AuthController controller =
          new AuthController(h.service, new CorsProperties(), mock(ClientIpResolver.class));
      assertEquals(
          202,
          assertDoesNotThrow(
                  () ->
                      controller.requestPasswordReset(
                          new PasswordResetRequestDto(h.user.getEmail())))
              .getStatusCode()
              .value());
      verify(h.tokens).save(any());
      verify(h.mail)
          .publishVerificationEmail(eq(h.user.getId()), eq("PASSWORD_RESET"), anyString());
      assertTrue(events.list.stream().anyMatch(e -> e.getLevel() == Level.WARN));
    } finally {
      logger.detachAppender(events);
      events.stop();
    }
  }

  @Test
  void R1_K1_springConstructorUsesBoundLimitAndWindowInsteadOfCompatibilityDefaults() {
    Unit h = new Unit();
    doReturn(List.of(1L, 7000L), List.of(2L, 7000L))
        .when(h.redis)
        .execute(any(RedisScript.class), anyList(), any(Object[].class));
    new ApplicationContextRunner()
        .withUserConfiguration(MailSettings.class)
        .withPropertyValues(
            "app.auth.password-reset-mail.limit=1", "app.auth.password-reset-mail.window=PT7S")
        .withBean(UserRepository.class, () -> h.users)
        .withBean(RefreshTokenRepository.class, () -> mock(RefreshTokenRepository.class))
        .withBean(VerificationTokenRepository.class, () -> h.tokens)
        .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
        .withBean(JwtService.class, () -> mock(JwtService.class))
        .withBean(JwtProperties.class, JwtProperties::new)
        .withBean(BruteForceGuard.class, () -> mock(BruteForceGuard.class))
        .withBean(EmailNotificationPublisher.class, () -> h.mail)
        .withBean(StringRedisTemplate.class, () -> h.redis)
        .withBean(SystemAdminProperties.class, SystemAdminProperties::new)
        .withBean(RefreshTokenFamilyRevoker.class, () -> mock(RefreshTokenFamilyRevoker.class))
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(RateLimiter.class)
        .withBean(PasswordResetMailBudget.class)
        .withBean(AuthService.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              AuthService service = context.getBean(AuthService.class);
              service.requestPasswordReset(h.user.getEmail());
              service.requestPasswordReset(h.user.getEmail());
              verify(h.tokens).save(any());
              verify(h.mail)
                  .publishVerificationEmail(eq(h.user.getId()), eq("PASSWORD_RESET"), anyString());
              verify(h.redis, times(2))
                  .execute(
                      any(RedisScript.class),
                      eq(List.of("pwreset_mail:" + h.user.getId())),
                      eq("7000"));
            });
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PasswordResetMailProperties.class)
  static class MailSettings {}

  private static class Unit {
    final UserRepository users = mock(UserRepository.class);
    final VerificationTokenRepository tokens = mock(VerificationTokenRepository.class);
    final EmailNotificationPublisher mail = mock(EmailNotificationPublisher.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final User user =
        User.newUser(UUID.randomUUID(), UUID.randomUUID() + "@tracker.local", "hash", "Contract");
    final AuthService service;

    Unit() {
      when(users.findByEmail(anyString())).thenReturn(Optional.empty());
      when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
      service =
          new AuthService(
              users,
              mock(RefreshTokenRepository.class),
              tokens,
              mock(PasswordEncoder.class),
              mock(JwtService.class),
              new JwtProperties(),
              mock(BruteForceGuard.class),
              mail,
              redis,
              new SystemAdminProperties(),
              mock(RefreshTokenFamilyRevoker.class),
              Clock.systemUTC(),
              new PasswordResetMailBudget(
                  new RateLimiter(redis), new PasswordResetMailProperties()));
    }
  }

  @Nested
  @Tag("docker")
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  class DefaultBudget extends MailHarness {
    @Test
    void R1_K1_twentyHttpRequestsProduceExactlyThreeMailsAndTokens() throws Exception {
      User user = account();
      long start = System.nanoTime();
      for (int i = 0; i < 20; i++) accepted(reset(user.getEmail()));
      assertTrue(
          System.nanoTime() - start < Duration.ofMinutes(1).toNanos(),
          "20 istek bir dakikada tamamlanmadi");
      assertEquals("20", redis.opsForValue().get(key(user)));
      assertEquals(3, tokenCount(user));
      exactMails(user, 3);
    }

    @Test
    void K1_singleRequestProducesExactlyOneMailAndToken() throws Exception {
      User user = account();
      accepted(reset(user.getEmail()));
      assertEquals(1, tokenCount(user));
      exactMails(user, 1);
    }

    @Test
    void R2_K1_inBudgetExhaustedAndUnknownResponsesHaveIdenticalStatusBodyAndHeaders()
        throws Exception {
      User user = account();
      HttpResponse<String> first = reset(user.getEmail());
      accepted(first);
      accepted(reset(user.getEmail()));
      accepted(reset(user.getEmail()));
      HttpResponse<String> exhausted = reset(user.getEmail());
      HttpResponse<String> absent = reset(UUID.randomUUID() + "@tracker.local");
      for (HttpResponse<String> response : List.of(exhausted, absent)) {
        accepted(response);
        assertEquals(first.body(), response.body());
        assertEquals(stableHeaders(first), stableHeaders(response));
      }
      assertEquals(3, tokenCount(user));
      exactMails(user, 3);
    }

    @Test
    void K2_exhaustedAccountDoesNotConsumeAnotherAccountsBudget() throws Exception {
      User first = account();
      User other = account();
      for (int i = 0; i < 4; i++) accepted(reset(first.getEmail()));
      accepted(reset(other.getEmail()));
      assertEquals(3, tokenCount(first));
      assertEquals(1, tokenCount(other));
      exactMails(first, 3);
      exactMails(other, 1);
    }

    @Test
    void R3_unknownAccountCreatesNoBudgetKeyAndKnownKeyContainsOnlyUuid() {
      String absent = UUID.randomUUID() + "@tracker.local";
      Set<String> before = new HashSet<>(Objects.requireNonNull(redis.keys("pwreset_mail:*")));
      accepted(reset(absent));
      assertEquals(before, redis.keys("pwreset_mail:*"));
      User user = account();
      accepted(reset(user.getEmail()));
      Set<String> expected = new HashSet<>(before);
      expected.add(key(user));
      assertEquals(expected, redis.keys("pwreset_mail:*"));
      assertEquals("1", redis.opsForValue().get(key(user)));
      Set<String> all = Objects.requireNonNull(redis.keys("*"));
      assertTrue(all.stream().noneMatch(k -> k.contains(absent) || k.contains(user.getEmail())));
      assertTrue(
          all.stream().filter(k -> k.startsWith("pwreset_mail:")).noneMatch(k -> k.contains("@")));
      Long ttl = redis.getExpire(key(user), TimeUnit.MILLISECONDS);
      assertNotNull(ttl);
      assertTrue(ttl > 0 && ttl <= 900_000);
    }

    @Test
    void R1_K1_orphanCounterGetsTtlAndEveryRejectedAttemptStillIncrements() throws Exception {
      User user = account();
      redis.opsForValue().set(key(user), "2");
      assertEquals(-1L, redis.getExpire(key(user)));
      accepted(reset(user.getEmail()));
      Long initialTtl = redis.getExpire(key(user), TimeUnit.MILLISECONDS);
      assertNotNull(initialTtl);
      assertTrue(initialTtl > 0 && initialTtl <= 900_000);
      Thread.sleep(100);
      accepted(reset(user.getEmail()));
      assertEquals("4", redis.opsForValue().get(key(user)));
      assertTrue(redis.getExpire(key(user), TimeUnit.MILLISECONDS) < initialTtl);
      assertEquals(1, tokenCount(user));
      exactMails(user, 1);
    }
  }

  @Nested
  @Tag("docker")
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "app.auth.password-reset-mail.limit=1",
        "app.auth.password-reset-mail.window=PT2S"
      })
  class ShortWindow extends MailHarness {
    @Test
    void R1_K1_configuredWindowExpiresAndNextRequestProducesAnotherMail() throws Exception {
      User user = account();
      long start = System.nanoTime();
      accepted(reset(user.getEmail()));
      accepted(reset(user.getEmail()));
      assertTrue(
          System.nanoTime() - start < Duration.ofSeconds(2).toNanos(),
          "iki istek pencereye sigmadi");
      assertEquals("2", redis.opsForValue().get(key(user)));
      assertEquals(1, tokenCount(user));
      Thread.sleep(2200);
      assertFalse(Boolean.TRUE.equals(redis.hasKey(key(user))));
      accepted(reset(user.getEmail()));
      assertEquals("1", redis.opsForValue().get(key(user)));
      assertEquals(2, tokenCount(user));
      exactMails(user, 2);
    }
  }

  abstract static class MailHarness extends AbstractIntegrationTest {
    @Autowired AuthService auth;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    User account() {
      return auth.register(
          UUID.randomUUID() + "@tracker.local",
          SecurityFixture.PASSWORD,
          "Budget Contract",
          PrivacyNotice.CURRENT_VERSION);
    }

    HttpResponse<String> reset(String email) {
      int ip = IPS.getAndIncrement();
      return new SecurityHttp(port)
          .send(
              "POST",
              "/api/v1/auth/password-reset/request",
              null,
              null,
              "{\"email\":\"" + email + "\"}",
              Map.of(
                  "X-Forwarded-For",
                  "198." + ((ip >>> 16) & 255) + "." + ((ip >>> 8) & 255) + "." + (ip & 255)));
    }

    void exactMails(User user, int expected) throws Exception {
      MailpitInbox inbox = new MailpitInbox(p -> mailpitApiUrl(p), mapper);
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (inbox.count(user.getEmail(), "sifirlama") < expected && System.nanoTime() < deadline)
        Thread.sleep(250);
      assertEquals(expected, inbox.count(user.getEmail(), "sifirlama"));
      Thread.sleep(3000);
      assertEquals(expected, inbox.count(user.getEmail(), "sifirlama"));
    }

    int tokenCount(User user) throws Exception {
      try (Connection c = openMigratorConnection();
          var statement =
              c.prepareStatement(
                  "SELECT count(*) FROM verification_tokens WHERE user_id = ? AND purpose = 'PASSWORD_RESET'")) {
        statement.setObject(1, user.getId());
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next());
          return rows.getInt(1);
        }
      }
    }

    String key(User user) {
      return "pwreset_mail:" + user.getId();
    }
  }

  private static void accepted(HttpResponse<String> response) {
    assertEquals(202, response.statusCode(), response.body());
  }

  private static Map<String, List<String>> stableHeaders(HttpResponse<String> response) {
    Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.putAll(response.headers().map());
    // Date istek zamanina, X-Request-Id ise her HTTP istegine ozgudur.
    headers.remove("date");
    headers.remove("x-request-id");
    return headers;
  }
}
