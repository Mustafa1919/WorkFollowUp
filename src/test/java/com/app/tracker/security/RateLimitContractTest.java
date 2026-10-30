package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.*;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.BruteForceGuard;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.workspace.model.WorkspaceRole;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.NestedTestConfiguration;
import tools.jackson.databind.ObjectMapper;

/** Dilim 04: sabit beklentiler sozlesmeden; RANDOM_PORT ile gercek servlet yanitlari. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RateLimitContractTest extends AbstractIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/login";
  private static final String REGISTER = "/api/v1/auth/register";
  private static final String RESET = "/api/v1/auth/password-reset/request";
  private static final String INVITE = "/api/v1/workspaces/members/invitations";
  private static final String FEEDBACK = "/api/v1/feedback";

  @Autowired private ApplicationContext context;
  @LocalServerPort private int port;
  private Harness h;

  @BeforeAll
  void setUp() {
    h = new Harness(context, port);
  }

  @Test
  void R1_K1_twentySuccessfulLoginsAreAcceptedThenTwentyFirstIs429() {
    String ip = ip();
    Actor user = h.member();
    for (int i = 1; i <= 20; i++) {
      assertStatus(200, h.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip));
    }
    h.problem(h.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip), 60, ip);
  }

  @Test
  void R1_K1_allAttemptKindsConsumeOneBudgetRegardlessOfAccountOrPassword() {
    String ip = ip();
    Actor user = h.member();
    for (int i = 1; i <= 20; i++) {
      String email = i <= 2 ? user.email() : UUID.randomUUID() + "@tracker.local";
      String password = i == 1 ? SecurityFixture.PASSWORD : "wrong-contract-password";
      HttpResponse<String> response = h.anonymous(LOGIN, login(email, password), ip);
      if (i == 1) assertStatus(200, response);
      else assertTrue(response.statusCode() >= 400 && response.statusCode() < 429, response.body());
      assertEquals(String.valueOf(i), h.redis.opsForValue().get("rate:login:ip:" + ip));
    }
    h.problem(h.anonymous(LOGIN, login(UUID.randomUUID() + "@tracker.local", "wrong"), ip), 60, ip);
  }

  @Test
  void K1_wrongPasswordBelowLimitKeepsBusinessRuleResponse() {
    Actor user = h.member();
    // Projede yanlis parola 400 (BusinessRuleException); hiz siniri bunu degistirmemeli.
    assertStatus(400, h.anonymous(LOGIN, login(user.email(), "wrong-contract-password"), ip()));
  }

  static Stream<String> anonymousRules() {
    return Stream.of(REGISTER, RESET);
  }

  @ParameterizedTest
  @MethodSource("anonymousRules")
  void R2_K2_exactTenThen429AndOtherIpUnaffected(String path) {
    String ip = ip();
    int normal = path.equals(REGISTER) ? 201 : 202;
    for (int i = 1; i <= 10; i++) assertStatus(normal, h.anonymous(path, anonymousBody(path), ip));
    h.problem(h.anonymous(path, anonymousBody(path), ip), 60, ip);
    assertStatus(normal, h.anonymous(path, anonymousBody(path), ip()));
  }

  @Test
  void R2_K2_exhaustingLoginDoesNotConsumeRegisterOrAnotherIp() {
    String ip = ip();
    Actor user = h.member();
    for (int i = 0; i < 20; i++)
      assertStatus(200, h.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip));
    h.problem(h.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip), 60, ip);
    assertStatus(201, h.anonymous(REGISTER, anonymousBody(REGISTER), ip));
    assertStatus(200, h.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip()));
  }

  record UserRule(String name, String path, int limit, int seconds, int status) {}

  static Stream<UserRule> userRules() {
    return Stream.of(
        new UserRule("invitation", INVITE, 30, 3600, 201),
        new UserRule("telemetry-usage", "/api/v1/telemetry/usage", 30, 60, 202),
        new UserRule("telemetry-error", "/api/v1/telemetry/errors", 30, 60, 202),
        new UserRule("feedback", FEEDBACK, 5, 60, 201));
  }

  @ParameterizedTest
  @MethodSource("userRules")
  void R3_K3_R4_exactUserBudgetSurvivesIpRotationAndOtherUserIsUnaffected(UserRule rule) {
    Actor user = h.member();
    String key = "rate:" + rule.name() + ":user:" + user.userId();
    for (int i = 1; i <= rule.limit(); i++) {
      assertStatus(
          rule.status(),
          h.user(rule.path(), user, body(rule.path(), UUID.randomUUID().toString()), ip(), null));
    }
    String rotatedIp = ip();
    h.problem(
        h.user(rule.path(), user, body(rule.path(), UUID.randomUUID().toString()), rotatedIp, null),
        rule.seconds(),
        rotatedIp);
    assertEquals(String.valueOf(rule.limit() + 1), h.redis.opsForValue().get(key));
    Actor other = h.member();
    assertStatus(
        rule.status(),
        h.user(
            rule.path(), other, body(rule.path(), UUID.randomUUID().toString()), rotatedIp, null));
  }

  static Stream<String> sideEffectRules() {
    return Stream.of(INVITE, FEEDBACK);
  }

  @ParameterizedTest
  @MethodSource("sideEffectRules")
  void R5_K3_rejectedKeyLeavesNoIdempotencyRecordOrControllerRowAndCanBeReused(String path)
      throws Exception {
    Actor user = h.member();
    int limit = path.equals(INVITE) ? 30 : 5;
    String rule = path.equals(INVITE) ? "invitation" : "feedback";
    int status = 201;
    String rateKey = "rate:" + rule + ":user:" + user.userId();
    for (int i = 0; i < limit; i++)
      assertStatus(
          status, h.user(path, user, body(path, UUID.randomUUID().toString()), ip(), null));
    String idempotency = "rate-contract-" + UUID.randomUUID();
    String marker = UUID.randomUUID().toString();
    String requestBody = body(path, marker);
    String redisKey = h.idempotencyKey(path, user, idempotency);
    assertFalse(Boolean.TRUE.equals(h.redis.hasKey(redisKey)));
    h.problem(
        h.user(path, user, requestBody, ip(), idempotency),
        path.equals(INVITE) ? 3600 : 60,
        user.userId().toString());
    assertFalse(
        Boolean.TRUE.equals(h.redis.hasKey(redisKey)), "reddedilen istek kayit/kilit birakti");
    assertEquals(0, rowCount(path, marker));
    // Butceyi yeniden ac; ayni anahtar ve govde gercekten kabul edilmeli.
    h.redis.opsForValue().set(rateKey, "0", Duration.ofMinutes(1));
    assertStatus(status, h.user(path, user, requestBody, ip(), idempotency));
    assertEquals(1, rowCount(path, marker));
    assertTrue(
        Boolean.TRUE.equals(h.redis.hasKey(redisKey)),
        "kabul esi idempotency duzenegini de dogrular");
  }

  @Test
  void R5_K3_exhaustedUserIsRejectedBeforeWorkspaceValidationAndChainOrderIsCorrect() {
    Actor user = h.member();
    for (int i = 0; i < 5; i++)
      assertStatus(
          201, h.user(FEEDBACK, user, body(FEEDBACK, UUID.randomUUID().toString()), ip(), null));
    HttpResponse<String> response =
        h.http.send(
            "POST",
            FEEDBACK,
            user.accessToken(),
            UUID.randomUUID(),
            body(FEEDBACK, "blocked"),
            Map.of("X-Forwarded-For", ip(), "Idempotency-Key", UUID.randomUUID().toString()));
    h.problem(response, 60, user.userId().toString());
    List<String> filters =
        context.getBean("securityFilterChain", SecurityFilterChain.class).getFilters().stream()
            .map(filter -> filter.getClass().getSimpleName())
            .toList();
    int rate = filters.indexOf("RateLimitFilter");
    assertTrue(
        filters.indexOf("PatAuthenticationFilter") >= 0
            && rate > filters.indexOf("PatAuthenticationFilter"),
        filters.toString());
    assertTrue(filters.indexOf("WorkspaceContextFilter") > rate, filters.toString());
    assertTrue(filters.indexOf("IdempotencyFilter") > rate, filters.toString());
  }

  @Test
  void K5_R2_unlistedRequestsCreateNoRateKeysButListedRequestDoes() {
    Actor user = h.member();
    Set<String> before = h.redis.keys("rate:*");
    String name = "rate" + UUID.randomUUID().toString().replace("-", "");
    assertStatus(
        201,
        h.http.send(
            "POST",
            "/api/v1/tags",
            user.accessToken(),
            h.fixture.a().workspaceId(),
            "{\"name\":\"" + name + "\",\"color\":\"#123456\"}",
            Map.of("X-Forwarded-For", ip())));
    assertStatus(200, h.http.get("/api/v1/tags", user, h.fixture.a().workspaceId(), null));
    assertTrue(before.containsAll(h.redis.keys("rate:*")), "kural disi istek sayac olusturdu");
    String ip = ip();
    assertStatus(202, h.anonymous(RESET, anonymousBody(RESET), ip));
    assertEquals("1", h.redis.opsForValue().get("rate:password-reset:ip:" + ip));
  }

  @Test
  void R3_K3_anonymousUserEndpointReturns401WithoutCounter() {
    Set<String> before = h.redis.keys("rate:*");
    assertStatus(401, h.anonymous(FEEDBACK, body(FEEDBACK, "anonymous"), ip()));
    assertTrue(
        before.containsAll(h.redis.keys("rate:*")), "anonim kullanici kurali sayac olusturdu");
    assertStatus(201, h.user(FEEDBACK, h.member(), body(FEEDBACK, "authenticated"), ip(), null));
  }

  @Test
  void R2_K2_windowOrphanRepairPreservesCountAndExpiredWindowAcceptsAgain() throws Exception {
    String ip = ip();
    String key = "rate:password-reset:ip:" + ip;
    h.redis.opsForValue().set(key, "10");
    assertEquals(-1L, h.redis.getExpire(key));
    h.problem(h.anonymous(RESET, anonymousBody(RESET), ip), 60, ip);
    assertEquals("11", h.redis.opsForValue().get(key));
    Long ttl = h.redis.getExpire(key);
    assertNotNull(ttl);
    assertTrue(ttl >= 1 && ttl <= 60, "onarilan TTL=" + ttl);
    h.redis.expire(key, Duration.ofMillis(100));
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (Boolean.TRUE.equals(h.redis.hasKey(key)) && System.nanoTime() < deadline)
      Thread.sleep(20);
    assertFalse(Boolean.TRUE.equals(h.redis.hasKey(key)));
    assertStatus(202, h.anonymous(RESET, anonymousBody(RESET), ip));
    assertEquals("1", h.redis.opsForValue().get(key));
    String belowIp = ip();
    String belowKey = "rate:password-reset:ip:" + belowIp;
    h.redis.opsForValue().set(belowKey, "1");
    assertEquals(-1L, h.redis.getExpire(belowKey));
    assertStatus(202, h.anonymous(RESET, anonymousBody(RESET), belowIp));
    assertEquals("2", h.redis.opsForValue().get(belowKey));
    Long repairedBelowLimit = h.redis.getExpire(belowKey);
    assertNotNull(repairedBelowLimit);
    assertTrue(repairedBelowLimit >= 1 && repairedBelowLimit <= 60);
  }

  @Test
  void R8_K8_resolvedIpReachesBruteForceCountersAndLockDelayRemainIndependent() {
    Actor user = h.member();
    String client = ip();
    String forged = ip();
    HttpResponse<String> failed =
        h.anonymous(
            LOGIN,
            login(user.email(), "wrong-contract-password"),
            forged + ", " + client + ", 172.18.0.1");
    assertTrue(failed.statusCode() >= 400 && failed.statusCode() < 429);
    assertEquals("1", h.redis.opsForValue().get("login_fail:ip:" + client));
    assertFalse(Boolean.TRUE.equals(h.redis.hasKey("login_fail:ip:" + forged)));
    BruteForceGuard guard = context.getBean(BruteForceGuard.class);
    assertFalse(guard.checkBeforeAttempt(user.email(), client).locked());
    guard.recordFailure(user.email(), client);
    assertTrue(guard.checkBeforeAttempt(user.email(), client).throttled());
    HttpResponse<String> throttled =
        h.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip());
    assertTrue(throttled.statusCode() >= 400 && throttled.statusCode() < 429);
    assertFalse(throttled.body().contains("accessToken"));
    for (int i = 0; i < 8; i++) guard.recordFailure(user.email(), client);
    assertTrue(guard.checkBeforeAttempt(user.email(), client).locked());
    HttpResponse<String> locked =
        h.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip());
    assertTrue(locked.statusCode() >= 400 && locked.statusCode() < 429);
    assertFalse(locked.body().contains("accessToken"));
    Actor other = h.member();
    assertStatus(200, h.anonymous(LOGIN, login(other.email(), SecurityFixture.PASSWORD), ip()));
    guard.recordSuccess(user.email());
    assertFalse(guard.checkBeforeAttempt(user.email(), client).locked());
    assertFalse(guard.checkBeforeAttempt(user.email(), client).throttled());
    assertStatus(200, h.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip()));
  }

  @Test
  void R3_K3_K10_jwtAndPatUseSameUserBudgetAndPatStillAuthenticatesOnUnlistedRoute() {
    Actor user = h.member();
    HttpResponse<String> issued =
        h.http.post("/api/v1/access-tokens", user, null, "{\"name\":\"rate-contract\"}");
    assertStatus(201, issued);
    String pat = h.mapper.readTree(issued.body()).path("rawToken").asString();
    assertFalse(pat.isBlank());
    assertStatus(
        201,
        h.http.send(
            "POST",
            FEEDBACK,
            pat,
            h.fixture.a().workspaceId(),
            body(FEEDBACK, UUID.randomUUID().toString()),
            Map.of("X-Forwarded-For", ip())));
    for (int i = 0; i < 4; i++)
      assertStatus(
          201, h.user(FEEDBACK, user, body(FEEDBACK, UUID.randomUUID().toString()), ip(), null));
    h.problem(
        h.http.send(
            "POST",
            FEEDBACK,
            pat,
            h.fixture.a().workspaceId(),
            body(FEEDBACK, "pat-blocked"),
            Map.of("X-Forwarded-For", ip())),
        60,
        user.userId().toString());
    assertStatus(200, h.http.get("/api/v1/tags", pat, h.fixture.a().workspaceId(), null));
  }

  @Nested
  @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = "app.rate-limit.enabled=false")
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Disabled extends AbstractIntegrationTest {
    @Autowired private ApplicationContext disabledContext;
    @LocalServerPort private int disabledPort;

    @Test
    void K9_R1_allSevenRulesAreDisabledAndCreateNoCounters() {
      Harness configured = new Harness(disabledContext, disabledPort);
      Actor user = configured.member();
      Set<String> before = configured.redis.keys("rate:*");
      String ip = ip();
      for (int i = 0; i < 21; i++)
        assertStatus(
            200, configured.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip));
      for (String path : List.of(REGISTER, RESET))
        for (int i = 0; i < 11; i++)
          assertStatus(
              path.equals(REGISTER) ? 201 : 202,
              configured.anonymous(path, anonymousBody(path), ip));
      userRules()
          .forEach(
              rule -> {
                for (int i = 0; i <= rule.limit(); i++)
                  assertStatus(
                      rule.status(),
                      configured.user(
                          rule.path(),
                          user,
                          body(rule.path(), UUID.randomUUID().toString()),
                          ip,
                          null));
              });
      assertTrue(
          before.containsAll(configured.redis.keys("rate:*")), "kapali filtre sayac olusturdu");
    }
  }

  @Nested
  @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {"app.rate-limit.rules.login.limit=2", "app.rate-limit.rules.login.window=7s"})
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class Configured extends AbstractIntegrationTest {
    @Autowired private ApplicationContext configuredContext;
    @LocalServerPort private int configuredPort;

    @Test
    void K9_R1_R4_limitAndWindowCanBeOverriddenAndExactBoundaryStillApplies() {
      Harness configured = new Harness(configuredContext, configuredPort);
      Actor user = configured.member();
      String ip = ip();
      assertStatus(
          200, configured.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip));
      assertStatus(
          200, configured.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip));
      configured.problem(
          configured.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip), 7, ip);
      Long ttl = configured.redis.getExpire("rate:login:ip:" + ip);
      assertNotNull(ttl);
      assertTrue(ttl >= 1 && ttl <= 7);
      assertStatus(
          200, configured.anonymous(LOGIN, login(user.email(), SecurityFixture.PASSWORD), ip()));
    }
  }

  private static int rowCount(String path, String marker) throws Exception {
    String sql =
        path.equals(INVITE)
            ? "SELECT count(*) FROM workspace_invitations WHERE email = ?"
            : "SELECT count(*) FROM feedback WHERE message = ?";
    try (Connection connection = openMigratorConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, path.equals(INVITE) ? marker + "@tracker.local" : marker);
      try (var rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static String ip() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return "198."
        + random.nextInt(1, 255)
        + "."
        + random.nextInt(1, 255)
        + "."
        + random.nextInt(1, 255);
  }

  private static String login(String email, String password) {
    return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
  }

  private static String anonymousBody(String path) {
    String email = UUID.randomUUID() + "@tracker.local";
    return path.equals(REGISTER)
        ? "{\"email\":\""
            + email
            + "\",\"password\":\""
            + SecurityFixture.PASSWORD
            + "\",\"fullName\":\"Rate Contract\",\"privacyNoticeAccepted\":true,\"privacyNoticeVersion\":\""
            + PrivacyNotice.CURRENT_VERSION
            + "\"}"
        : "{\"email\":\"" + email + "\"}";
  }

  private static String body(String path, String marker) {
    if (path.equals(INVITE))
      return "{\"email\":\""
          + marker
          + "@tracker.local\",\"role\":\""
          + WorkspaceRole.VIEWER
          + "\"}";
    if (path.endsWith("/usage"))
      return "{\"events\":[{\"feature\":\"board\",\"action\":\"open\"}]}";
    if (path.endsWith("/errors"))
      return "{\"errorType\":\"Contract\",\"message\":\"" + marker + "\",\"path\":\"/board\"}";
    return "{\"message\":\"" + marker + "\",\"pagePath\":\"/board\"}";
  }

  private static void assertStatus(int status, HttpResponse<String> response) {
    assertEquals(status, response.statusCode(), response.body());
  }

  private static final class Harness {
    final SecurityFixture fixture;
    final SecurityHttp http;
    final StringRedisTemplate redis;
    final ObjectMapper mapper;

    Harness(ApplicationContext context, int port) {
      fixture = SecurityFixture.create(context, port);
      http = new SecurityHttp(port);
      redis = context.getBean(StringRedisTemplate.class);
      mapper = context.getBean(ObjectMapper.class);
    }

    Actor member() {
      return fixture.addMember(fixture.a(), WorkspaceRole.ADMIN, "rate-contract");
    }

    HttpResponse<String> anonymous(String path, String body, String ip) {
      return http.send("POST", path, null, null, body, Map.of("X-Forwarded-For", ip));
    }

    HttpResponse<String> user(String path, Actor user, String body, String ip, String key) {
      Map<String, String> headers =
          key == null
              ? Map.of("X-Forwarded-For", ip)
              : Map.of("X-Forwarded-For", ip, "Idempotency-Key", key);
      return http.send("POST", path, user.accessToken(), fixture.a().workspaceId(), body, headers);
    }

    String idempotencyKey(String path, Actor user, String key) throws Exception {
      String scope =
          user.userId() + "\n" + fixture.a().workspaceId() + "\nPOST\n" + path + "\n" + key;
      return "idempotency:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(scope.getBytes(StandardCharsets.UTF_8)));
    }

    void problem(HttpResponse<String> response, int windowSeconds, String secret) {
      assertStatus(429, response);
      long retry = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
      assertTrue(retry >= 1 && retry <= windowSeconds, "Retry-After=" + retry);
      assertTrue(
          response
              .headers()
              .firstValue("Content-Type")
              .orElse("")
              .startsWith("application/problem+json"));
      var json = mapper.readTree(response.body());
      assertEquals("https://api.app.com/errors/rate-limited", json.path("type").asString());
      assertEquals("Cok Fazla Istek", json.path("title").asString());
      assertEquals(429, json.path("status").asInt());
      assertFalse(json.path("detail").asString("").isBlank());
      assertFalse(response.body().contains(secret), response.body());
      for (String internal :
          List.of(
              "rate:",
              "Redis",
              "RateLimitFilter",
              "RateLimiter",
              "ClientIpResolver",
              "Exception",
              "com.app.",
              "IN_PROGRESS",
              "stackTrace",
              "127.0.0.1")) assertFalse(response.body().contains(internal), response.body());
    }
  }
}
