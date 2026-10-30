package com.app.tracker.core.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.app.tracker.core.security.AuthController;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.security.CorsProperties;
import com.app.tracker.core.security.PatProperties;
import com.app.tracker.core.security.PatRateLimiter;
import com.app.tracker.core.security.dto.LoginRequest;
import com.app.tracker.core.web.ClientIpProperties;
import com.app.tracker.core.web.ClientIpResolver;
import jakarta.servlet.FilterChain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

/** Limitler ve yollar sozlesmeden sabittir; Redis taklidi yalniz sayac/TTL sonucu verir. */
class RateLimitUnitContractTest {

  static Stream<Arguments> rules() {
    return Stream.of(
        Arguments.of("login", "/api/v1/auth/login", 20, 60, false),
        Arguments.of("register", "/api/v1/auth/register", 10, 60, false),
        Arguments.of("password-reset", "/api/v1/auth/password-reset/request", 10, 60, false),
        Arguments.of("invitation", "/api/v1/workspaces/members/invitations", 30, 3600, true),
        Arguments.of("telemetry-usage", "/api/v1/telemetry/usage", 30, 60, true),
        Arguments.of("telemetry-error", "/api/v1/telemetry/errors", 30, 60, true),
        Arguments.of("feedback", "/api/v1/feedback", 5, 60, true));
  }

  private final CounterRedis redis = new CounterRedis();
  private final ClientIpResolver resolver = new ClientIpResolver(new ClientIpProperties());

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  static class CounterRedis extends StringRedisTemplate {
    final Map<String, Long> counts = new HashMap<>();
    final Map<String, Long> windows = new HashMap<>();
    boolean unavailable;

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
      if (unavailable) {
        throw new RedisConnectionFailureException("contract-redis-offline");
      }
      String key = keys.getFirst();
      long window = Long.parseLong(args[0].toString());
      windows.put(key, window);
      return (T) List.of(counts.merge(key, 1L, Long::sum), window);
    }
  }

  private RateLimitFilter filter(RateLimitProperties properties) {
    return new RateLimitFilter(new RateLimiter(redis), resolver, properties);
  }

  private RateLimitFilter filter() {
    return filter(new RateLimitProperties());
  }

  private static void authenticate(UUID user) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }

  private static MockHttpServletRequest request(String method, String path, String ip) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("X-Forwarded-For", ip);
    return request;
  }

  private static MockHttpServletResponse call(RateLimitFilter filter, String path, String ip)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request("POST", path, ip),
        response,
        (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(202));
    return response;
  }

  @ParameterizedTest
  @MethodSource("rules")
  void R1_R2_R3_R4_K1_K3_exactBoundaryAndUserCannotRotateIp(
      String name, String path, int limit, int seconds, boolean userRule) throws Exception {
    UUID user = UUID.randomUUID();
    authenticate(user);
    RateLimitFilter filter = filter();
    for (int i = 1; i <= limit; i++) {
      assertEquals(
          202, call(filter, path, userRule ? "198.51.100." + i : "198.51.100.7").getStatus());
    }
    MockHttpServletResponse over = call(filter, path, "198.51.100." + (userRule ? 99 : 7));
    String key = "rate:" + name + (userRule ? ":user:" + user : ":ip:198.51.100.7");
    assertEquals(429, over.getStatus());
    assertEquals((long) limit + 1, redis.counts.get(key));
    assertEquals(seconds * 1000L, redis.windows.get(key));
    long retry = Long.parseLong(over.getHeader("Retry-After"));
    assertTrue(retry >= 1 && retry <= seconds);
    assertTrue(over.getContentType().startsWith("application/problem+json"));
    var body = new ObjectMapper().readTree(over.getContentAsString());
    assertEquals("https://api.app.com/errors/rate-limited", body.path("type").asString());
    assertEquals("Cok Fazla Istek", body.path("title").asString());
    assertEquals(429, body.path("status").asInt());
    assertFalse(body.path("detail").asString("").isBlank());
    assertFalse(
        over.getContentAsString()
            .matches("(?s).*(rate:|198\\.51|Redis|Exception|com\\.app|" + user + ").*"));
    assertNull(over.getErrorMessage(), "sendError kullanilmamali");
    if (userRule) {
      authenticate(UUID.randomUUID());
      assertEquals(202, call(filter, path, "198.51.100.99").getStatus());
    } else {
      assertEquals(202, call(filter, path, "203.0.113.99").getStatus());
    }
  }

  @Test
  void R2_K2_rulesAndIpsHaveIndependentBudgets() throws Exception {
    RateLimitFilter filter = filter();
    for (int i = 0; i < 20; i++)
      assertEquals(202, call(filter, "/api/v1/auth/login", "198.51.100.7").getStatus());
    assertEquals(429, call(filter, "/api/v1/auth/login", "198.51.100.7").getStatus());
    assertEquals(202, call(filter, "/api/v1/auth/register", "198.51.100.7").getStatus());
    assertEquals(202, call(filter, "/api/v1/auth/login", "198.51.100.8").getStatus());
  }

  @Test
  void R5_K3_denialStopsChainAndAcceptanceInvokesIt() throws Exception {
    RateLimitFilter filter = filter();
    authenticate(UUID.randomUUID());
    FilterChain chain = mock(FilterChain.class);
    for (int i = 0; i < 5; i++)
      filter.doFilter(
          request("POST", "/api/v1/feedback", "198.51.100.7"),
          new MockHttpServletResponse(),
          chain);
    verify(chain, times(5)).doFilter(any(), any());
    clearInvocations(chain);
    MockHttpServletRequest rejected = request("POST", "/api/v1/feedback", "198.51.100.8");
    rejected.addHeader("Idempotency-Key", UUID.randomUUID().toString());
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(rejected, response, chain);
    assertEquals(429, response.getStatus());
    verifyNoInteractions(chain);
  }

  static Stream<Arguments> unlisted() {
    return Stream.of(
        Arguments.of("GET", "/api/v1/auth/login"),
        Arguments.of("POST", "/api/v1/auth/login-extra"),
        Arguments.of("POST", "/api/v1/tags"),
        Arguments.of("GET", "/api/v1/tags"),
        Arguments.of("POST", "/api/v1/feedback-extra"),
        Arguments.of("POST", "/api/v1/workspaces"));
  }

  @ParameterizedTest
  @MethodSource("unlisted")
  void K5_R2_unlistedRoutesNeverTouchRedisAndListedRouteDoes(String method, String path)
      throws Exception {
    RateLimitFilter filter = filter();
    authenticate(UUID.randomUUID());
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request(method, path, "198.51.100.7"),
        response,
        (a, b) -> ((jakarta.servlet.http.HttpServletResponse) b).setStatus(202));
    assertEquals(202, response.getStatus());
    assertTrue(redis.counts.isEmpty());
    assertEquals(202, call(filter, "/api/v1/auth/register", "198.51.100.7").getStatus());
    assertEquals(1, redis.counts.size());
  }

  @Test
  void R3_K3_anonymousUserRuleSkipsRedisButAuthenticatedRuleCounts() throws Exception {
    assertEquals(202, call(filter(), "/api/v1/feedback", "198.51.100.7").getStatus());
    assertTrue(redis.counts.isEmpty());
    authenticate(UUID.randomUUID());
    assertEquals(202, call(filter(), "/api/v1/feedback", "198.51.100.7").getStatus());
    assertEquals(1, redis.counts.size());
  }

  @Test
  void R9_K1_redisFailureAllowsRequestAndEmitsWarnThenHealthyLimitStillRejects() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(RateLimiter.class);
    ListAppender<ILoggingEvent> capture = new ListAppender<>();
    capture.start();
    logger.addAppender(capture);
    try {
      redis.unavailable = true;
      assertEquals(202, call(filter(), "/api/v1/auth/login", "198.51.100.7").getStatus());
      assertTrue(capture.list.stream().anyMatch(event -> event.getLevel() == Level.WARN));
      redis.unavailable = false;
      redis.counts.put("rate:login:ip:198.51.100.7", 20L);
      assertEquals(429, call(filter(), "/api/v1/auth/login", "198.51.100.7").getStatus());
    } finally {
      logger.detachAppender(capture);
      capture.stop();
    }
  }

  @Test
  void R4_K1_subsecondRemainingWindowRoundsUpToOneSecond() {
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(List.of(21L, 1L));
    RateLimiter.Decision denied =
        new RateLimiter(template).hit("rate:login:ip:198.51.100.7", 20, Duration.ofMinutes(1));
    assertFalse(denied.allowed());
    assertEquals(1, denied.retryAfterSeconds());
    when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(List.of(20L, 1L));
    assertTrue(
        new RateLimiter(template)
            .hit("rate:login:ip:198.51.100.7", 20, Duration.ofMinutes(1))
            .allowed());
  }

  @Test
  void R8_K6_controllerPassesResolvedIpToAuthService() {
    AuthService service = mock(AuthService.class);
    when(service.login(anyString(), anyString(), anyString()))
        .thenReturn(new AuthService.LoginResult("access", "refresh", Duration.ofMinutes(1)));
    AuthController controller = new AuthController(service, new CorsProperties(), resolver);
    MockHttpServletRequest direct = request("POST", "/api/v1/auth/login", "1.1.1.1");
    direct.setRemoteAddr("203.0.113.9");
    assertEquals(
        200,
        controller
            .login(new LoginRequest("one@example.test", "password"), direct)
            .getStatusCode()
            .value());
    verify(service).login("one@example.test", "password", "203.0.113.9");
    MockHttpServletRequest proxied =
        request("POST", "/api/v1/auth/login", "6.6.6.6, 198.51.100.7, 172.18.0.1");
    assertEquals(
        200,
        controller
            .login(new LoginRequest("two@example.test", "password"), proxied)
            .getStatusCode()
            .value());
    verify(service).login("two@example.test", "password", "198.51.100.7");
  }

  @Test
  void K9_R1_configuredLimitAndWindowReplaceDefaults() throws Exception {
    RateLimitProperties properties = new RateLimitProperties();
    RateLimitProperties.RuleSettings login = new RateLimitProperties.RuleSettings();
    login.setLimit(2L);
    login.setWindow(Duration.ofSeconds(7));
    properties.setRules(Map.of("login", login));
    RateLimitFilter filter = filter(properties);
    assertEquals(202, call(filter, "/api/v1/auth/login", "198.51.100.7").getStatus());
    assertEquals(202, call(filter, "/api/v1/auth/login", "198.51.100.7").getStatus());
    MockHttpServletResponse over = call(filter, "/api/v1/auth/login", "198.51.100.7");
    assertEquals(429, over.getStatus());
    assertEquals("7", over.getHeader("Retry-After"));
    assertEquals(7000L, redis.windows.get("rate:login:ip:198.51.100.7"));
  }

  @ParameterizedTest
  @MethodSource("rules")
  void K9_R1_R2_R3_disabledRulesNeverTouchRedis(
      String name, String path, int limit, int seconds, boolean userRule) throws Exception {
    authenticate(UUID.randomUUID());
    RateLimitProperties properties = new RateLimitProperties();
    properties.setEnabled(false);
    RateLimitFilter disabled = filter(properties);
    for (int i = 0; i <= limit; i++) {
      assertEquals(202, call(disabled, path, "198.51.100.7").getStatus());
    }
    assertTrue(redis.counts.isEmpty());
    RateLimitFilter enabled = filter();
    for (int i = 0; i < limit; i++)
      assertEquals(202, call(enabled, path, "198.51.100.7").getStatus());
    assertEquals(429, call(enabled, path, "198.51.100.7").getStatus());
  }

  @Test
  void R8_onlyResolverContainsRawForwardedForReader() throws Exception {
    try (var paths = Files.walk(Path.of("src/main/java"))) {
      List<Path> readers =
          paths
              .filter(path -> path.toString().endsWith(".java"))
              .filter(
                  path -> {
                    try {
                      return Files.readString(path).contains("\"X-Forwarded-For\"");
                    } catch (java.io.IOException e) {
                      throw new java.io.UncheckedIOException(e);
                    }
                  })
              .toList();
      assertEquals(
          List.of(Path.of("src/main/java/com/app/tracker/core/web/ClientIpResolver.java")),
          readers);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void K10_tokenBudgetStillAcceptsSixtyThenRejectsAndOtherTokenIsIndependent() {
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    Map<String, Long> counts = new HashMap<>();
    when(template.opsForValue()).thenReturn(values);
    when(values.increment(anyString()))
        .thenAnswer(call -> counts.merge(call.getArgument(0), 1L, Long::sum));
    when(template.getExpire(anyString())).thenReturn(60L);
    PatRateLimiter limiter = new PatRateLimiter(template, new PatProperties());
    UUID token = UUID.randomUUID();
    for (int i = 1; i <= 60; i++) assertTrue(limiter.checkAndIncrement(token).allowed());
    assertFalse(limiter.checkAndIncrement(token).allowed());
    assertTrue(limiter.checkAndIncrement(UUID.randomUUID()).allowed());
  }
}
