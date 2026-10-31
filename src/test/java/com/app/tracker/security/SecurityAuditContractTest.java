package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.app.tracker.accesstoken.repository.PersonalAccessTokenRepository;
import com.app.tracker.accesstoken.service.PersonalAccessTokenService;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.audit.SecurityAuditEvent;
import com.app.tracker.core.audit.SecurityAuditLog;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.GlobalExceptionHandler;
import com.app.tracker.core.notification.EmailNotificationPublisher;
import com.app.tracker.core.realtime.WebSocketAuthInterceptor;
import com.app.tracker.core.realtime.WebSocketDeliveryGuard;
import com.app.tracker.core.realtime.WebSocketSessionRegistry;
import com.app.tracker.core.security.*;
import com.app.tracker.core.security.model.RefreshToken;
import com.app.tracker.core.security.model.VerificationToken;
import com.app.tracker.core.security.repository.RefreshTokenRepository;
import com.app.tracker.core.security.repository.VerificationTokenRepository;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.core.tenancy.WorkspaceContextFilter;
import com.app.tracker.security.support.MailpitInbox;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import com.app.tracker.workspace.service.WorkspaceMemberService;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import jakarta.servlet.FilterChain;
import java.lang.reflect.Constructor;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Expectations: dilim-05-sozlesme.md. Run the flat Service companion for Docker-free checks. Select
 * both classes with -Dtest=SecurityAuditContractTest,SecurityAuditServiceContractTest. Pending
 * Docker mutations and evidence: target/d05-dogrulama.md.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.http.trusted-proxies[0]=127.0.0.0/8", "app.http.trusted-proxies[1]=::1/128"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityAuditContractTest extends AbstractIntegrationTest {
  @LocalServerPort private int port;
  @Autowired private ApplicationContext context;
  @Autowired private ObjectMapper mapper;
  private SecurityFixture fixture;
  private SecurityHttp http;
  private AuditContractProbe probe;
  private String clientIp;

  @BeforeAll
  void fixture() {
    fixture = SecurityFixture.create(context, port);
    http = fixture.http();
  }

  @BeforeEach
  void capture() {
    probe = new AuditContractProbe();
    String hex = UUID.randomUUID().toString().replace("-", "");
    clientIp =
        "2001:db8::"
            + Integer.toHexString(0x1000 | Integer.parseInt(hex.substring(0, 4), 16))
            + ":"
            + Integer.toHexString(0x1000 | Integer.parseInt(hex.substring(4, 8), 16));
    probe.watch("ip=" + clientIp);
    for (var actor : fixture.a().actors().values()) {
      probe.watch(actor.userId());
      probe.secret(actor.email());
      probe.secret(actor.accessToken());
    }
    probe.watch(fixture.a().workspaceId());
    probe.watch(fixture.outsider().userId());
    probe.secret(SecurityFixture.PASSWORD);
  }

  @AfterEach
  void release() {
    try {
      probe.noSecrets();
    } finally {
      probe.close();
    }
  }

  private AuthService auth() {
    return context.getBean(AuthService.class);
  }

  private HttpResponse<String> login(String email, String password) throws Exception {
    return http.send(
        "POST",
        "/api/v1/auth/login",
        null,
        null,
        mapper.writeValueAsString(Map.of("email", email, "password", password)),
        Map.of("X-Forwarded-For", clientIp));
  }

  @Test
  void K1_R1_R2_wrongPasswordHasOneAuditAndCorrectPasswordSucceeds() throws Exception {
    var actor = fixture.a().viewer();
    String wrong = "wrong-" + UUID.randomUUID();
    probe.secret(wrong);
    assertEquals(400, login(actor.email(), wrong).statusCode());
    probe.exact(
        "LOGIN_FAILED", actor.userId(), Map.of("ip", clientIp, "reason", "bad_credentials"));
    probe.clear();
    assertEquals(200, login(actor.email(), SecurityFixture.PASSWORD).statusCode());
    assertTrue(probe.rows().isEmpty());
  }

  @Test
  void K2_R2_missingAccountDoesNotDiscloseEmail() throws Exception {
    String email = "audit-missing-" + UUID.randomUUID() + "@tracker.local";
    probe.secret(email);
    assertEquals(400, login(email, SecurityFixture.PASSWORD).statusCode());
    probe.exact("LOGIN_FAILED", null, Map.of("ip", clientIp, "reason", "bad_credentials"));
    assertEquals(200, login(fixture.a().admin().email(), SecurityFixture.PASSWORD).statusCode());
  }

  @Test
  void K3_R1_R2_lockTransitionAuditsOnceAndSubsequentHttpAttemptsAreLocked() throws Exception {
    String email = "audit-lock-" + UUID.randomUUID() + "@tracker.local";
    User user =
        auth()
            .register(email, SecurityFixture.PASSWORD, "Audit lock", PrivacyNotice.CURRENT_VERSION);
    probe.watch(user.getId());
    probe.secret(email);
    assertEquals(200, login(email, SecurityFixture.PASSWORD).statusCode());
    assertTrue(probe.rows().isEmpty());
    long threshold =
        (Long) ReflectionTestUtils.getField(BruteForceGuard.class, "USER_LOCK_THRESHOLD");
    context
        .getBean(StringRedisTemplate.class)
        .opsForValue()
        .set("login_fail:user:" + email, Long.toString(threshold - 1), Duration.ofMinutes(1));
    assertEquals(400, login(email, "wrong-audit-password").statusCode());
    probe.secret("wrong-audit-password");
    probe.exact("ACCOUNT_LOCKED", user.getId(), Map.of("ip", clientIp));
    probe.exact("LOGIN_FAILED", user.getId(), Map.of("ip", clientIp, "reason", "bad_credentials"));
    for (int i = 0; i < 2; i++)
      assertEquals(400, login(email, SecurityFixture.PASSWORD).statusCode());
    assertEquals(1, probe.events("ACCOUNT_LOCKED").size());
    assertEquals(3, probe.events("LOGIN_FAILED").size());
    assertEquals(
        2,
        probe.events("LOGIN_FAILED").stream()
            .filter(e -> e.getFormattedMessage().endsWith(" reason=locked"))
            .count());
  }

  @Test
  void K4_R1_R2_reuseAuditsOnlyAfterFamilyRevocationCommits() throws Exception {
    var actor = fixture.a().developer();
    var pair = auth().login(actor.email(), SecurityFixture.PASSWORD, "127.0.0.1");
    probe.secret(pair.refreshToken());
    probe.secret(pair.accessToken());
    var rotated = auth().refresh(pair.refreshToken());
    assertNotNull(rotated.accessToken());
    assertTrue(probe.rows().isEmpty());
    UUID family;
    try (Connection c = openMigratorConnection();
        var s =
            c.prepareStatement(
                "UPDATE refresh_tokens SET used_at = now() - interval '60 seconds' WHERE token_hash = ? RETURNING family_id")) {
      s.setString(1, TokenHasher.sha256Hex(pair.refreshToken()));
      try (var rs = s.executeQuery()) {
        assertTrue(rs.next());
        family = rs.getObject(1, UUID.class);
      }
    }
    assertThrows(RefreshTokenReuseException.class, () -> auth().refresh(pair.refreshToken()));
    try (Connection c = openMigratorConnection();
        var s =
            c.prepareStatement(
                "SELECT count(*), count(*) FILTER (WHERE revoked) FROM refresh_tokens WHERE family_id = ?")) {
      s.setObject(1, family);
      try (var rs = s.executeQuery()) {
        assertTrue(rs.next());
        assertTrue(rs.getLong(1) >= 2);
        assertEquals(rs.getLong(1), rs.getLong(2), "REQUIRES_NEW must survive outer rollback");
      }
    }
    probe.exact("REFRESH_TOKEN_REUSE", actor.userId(), Map.of("family", family.toString()));
  }

  @Test
  void K5_R1_R2_developer403AndAdminAcceptanceExcludeQuery() throws Exception {
    UUID workspace = fixture.a().workspaceId();
    String query = "auditQuerySecret" + UUID.randomUUID();
    probe.secret(query);
    var denied =
        http.post(
            "/api/v1/projects?probe=" + query,
            fixture.a().developer(),
            workspace,
            "{\"key\":\"AUD\",\"name\":\"Audit project\"}");
    assertEquals(403, denied.statusCode(), denied.body());
    probe.exact(
        "ACCESS_DENIED",
        fixture.a().developer().userId(),
        Map.of("method", "POST", "path", "/api/v1/projects", "workspace", workspace.toString()));
    probe.clear();
    var accepted =
        http.post(
            "/api/v1/projects",
            fixture.a().admin(),
            workspace,
            "{\"key\":\"A"
                + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT)
                + "\",\"name\":\"Audit accepted\"}");
    assertEquals(201, accepted.statusCode(), accepted.body());
    assertTrue(probe.rows().isEmpty());
  }

  @Test
  void K5_R1_R2_nonmemberFilter403AndMemberAcceptance() {
    UUID workspace = fixture.a().workspaceId();
    var denied =
        http.get(
            "/api/v1/workspaces/members?probe=privateQueryD05",
            fixture.outsider(),
            workspace,
            null);
    probe.secret("privateQueryD05");
    assertEquals(403, denied.statusCode(), denied.body());
    probe.exact(
        "ACCESS_DENIED",
        fixture.outsider().userId(),
        Map.of(
            "method",
            "GET",
            "path",
            "/api/v1/workspaces/members",
            "workspace",
            workspace.toString()));
    probe.clear();
    assertEquals(
        200,
        http.get("/api/v1/workspaces/members", fixture.a().viewer(), workspace, null).statusCode());
    assertTrue(probe.rows().isEmpty());
  }

  @Test
  void K6_R2_patCreationAndRevocationLogOnlyTokenId() throws Exception {
    var actor = fixture.a().admin();
    var created =
        http.post(
            "/api/v1/access-tokens", actor, null, "{\"name\":\"audit PAT\",\"expiresInDays\":1}");
    assertEquals(201, created.statusCode(), created.body());
    var json = mapper.readTree(created.body());
    String raw = json.path("rawToken").asString();
    assertFalse(raw.isBlank());
    probe.secret(raw);
    String id = json.path("token").path("id").asString();
    assertFalse(id.isBlank());
    probe.exact("PAT_CREATED", actor.userId(), Map.of("token", id));
    assertEquals(204, http.delete("/api/v1/access-tokens/" + id, actor, null, null).statusCode());
    probe.exact("PAT_REVOKED", actor.userId(), Map.of("token", id));
  }

  @Test
  void K6_R1_R2_membershipChangesIdentifyActorTargetAndNewRole() throws Exception {
    UUID workspace = fixture.a().workspaceId();
    var admin = fixture.a().admin();
    var target = fixture.outsider();
    probe.secret(target.email());
    var added =
        http.post(
            "/api/v1/workspaces/members",
            admin,
            workspace,
            mapper.writeValueAsString(
                Map.of("email", target.email(), "role", WorkspaceRole.VIEWER)));
    assertEquals(201, added.statusCode(), added.body());
    probe.exact(
        "MEMBER_ADDED",
        admin.userId(),
        Map.of(
            "workspace",
            workspace.toString(),
            "target",
            target.userId().toString(),
            "role",
            WorkspaceRole.VIEWER));
    assertEquals(
        200,
        http.patch(
                "/api/v1/workspaces/members/" + target.userId(),
                admin,
                workspace,
                mapper.writeValueAsString(Map.of("role", WorkspaceRole.DEVELOPER)))
            .statusCode());
    probe.exact(
        "MEMBER_ROLE_CHANGED",
        admin.userId(),
        Map.of(
            "workspace",
            workspace.toString(),
            "target",
            target.userId().toString(),
            "role",
            WorkspaceRole.DEVELOPER));
    assertEquals(
        204,
        http.delete("/api/v1/workspaces/members/" + target.userId(), admin, workspace, null)
            .statusCode());
    probe.exact(
        "MEMBER_REMOVED",
        admin.userId(),
        Map.of("workspace", workspace.toString(), "target", target.userId().toString()));
    probe.clear();
    assertEquals(200, http.get("/api/v1/workspaces/members", admin, workspace, null).statusCode());
    assertTrue(probe.rows().isEmpty());
  }

  @Test
  void K7_R2_passwordResetLogsCompletionWithoutResetSecret() throws Exception {
    String email = "audit-reset-" + UUID.randomUUID() + "@tracker.local";
    User user =
        auth()
            .register(
                email, SecurityFixture.PASSWORD, "Audit reset", PrivacyNotice.CURRENT_VERSION);
    probe.watch(user.getId());
    probe.secret(email);
    String raw = TokenHasher.generateRawToken();
    probe.secret(raw);
    String password = "reset-new-" + UUID.randomUUID();
    probe.secret(password);
    context
        .getBean(VerificationTokenRepository.class)
        .save(
            VerificationToken.issue(
                UUID.randomUUID(),
                user.getId(),
                TokenHasher.sha256Hex(raw),
                "PASSWORD_RESET",
                Instant.now().plusSeconds(600)));
    var response =
        http.post(
            "/api/v1/auth/password-reset/confirm",
            (String) null,
            null,
            mapper.writeValueAsString(Map.of("token", raw, "newPassword", password)));
    assertEquals(204, response.statusCode(), response.body());
    probe.exact("PASSWORD_RESET_COMPLETED", user.getId(), Map.of());
    assertEquals(200, login(email, password).statusCode());
  }

  @Test
  void R2_K5_invitationTokenAndHashInQueryNeverReachAuditFields() throws Exception {
    UUID workspace = fixture.a().workspaceId();
    String email = "audit-invite-" + UUID.randomUUID() + "@tracker.local";
    probe.secret(email);
    var invited =
        http.post(
            "/api/v1/workspaces/members/invitations",
            fixture.a().admin(),
            workspace,
            mapper.writeValueAsString(Map.of("email", email, "role", WorkspaceRole.VIEWER)));
    assertEquals(201, invited.statusCode(), invited.body());
    String raw =
        new MailpitInbox(SecurityAuditContractTest::mailpitApiUrl, mapper).inviteToken(email);
    probe.secret(raw);
    probe.clear();
    String query = "invitation=" + raw + "&hash=" + TokenHasher.sha256Hex(raw);
    probe.secret(query);
    var denied =
        http.post(
            "/api/v1/projects?" + query,
            fixture.a().developer(),
            workspace,
            "{\"key\":\"INV\",\"name\":\"Audit invitation secret\"}");
    assertEquals(403, denied.statusCode(), denied.body());
    probe.exact(
        "ACCESS_DENIED",
        fixture.a().developer().userId(),
        Map.of("method", "POST", "path", "/api/v1/projects", "workspace", workspace.toString()));
    probe.clear();
    assertEquals(
        200, http.get("/api/v1/projects", fixture.a().admin(), workspace, null).statusCode());
    assertTrue(probe.rows().isEmpty());
  }
}

/** Flat companion: mvnw test -Dtest=SecurityAuditServiceContractTest (no Docker). */
class SecurityAuditServiceContractTest {
  private final UUID actor = UUID.randomUUID();
  private final UUID workspace = UUID.randomUUID();
  private final String email = "audit-service-" + UUID.randomUUID() + "@tracker.local";
  private final String password = "password-" + UUID.randomUUID();
  private final String ip =
      "2001:db8::"
          + UUID.randomUUID()
              .toString()
              .substring(0, 8)
              .replaceAll("(.{4})", "$1:")
              .replaceAll(":$", "");
  private final Instant now = Instant.now();
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private AuditContractProbe probe;
  private UserRepository users;
  private RefreshTokenRepository refresh;
  private VerificationTokenRepository verification;
  private PasswordEncoder encoder;
  private JwtService jwt;
  private BruteForceGuard guard;
  private RefreshTokenFamilyRevoker revoker;
  private StringRedisTemplate redis;
  private ValueOperations<String, String> values;
  private SecurityAuditLog audit;
  private AuthService auth;
  private User user;

  @BeforeEach
  void setUp() {
    probe = new AuditContractProbe();
    probe.watch(actor);
    probe.watch(workspace);
    probe.watch("ip=" + ip);
    probe.secret(email);
    probe.secret(password);
    users = mock(UserRepository.class);
    refresh = mock(RefreshTokenRepository.class);
    verification = mock(VerificationTokenRepository.class);
    encoder = mock(PasswordEncoder.class);
    jwt = mock(JwtService.class);
    guard = mock(BruteForceGuard.class);
    revoker = mock(RefreshTokenFamilyRevoker.class);
    redis = mock(StringRedisTemplate.class);
    values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(encoder.encode(anyString())).thenReturn("passwordHashD05");
    when(guard.checkBeforeAttempt(anyString(), anyString()))
        .thenReturn(new BruteForceGuard.CheckResult(false, false, false));
    user = User.newUser(actor, email, "passwordHashD05", "Audit service");
    when(users.findByEmail(email)).thenReturn(Optional.of(user));
    when(users.findById(actor)).thenReturn(Optional.of(user));
    when(jwt.issueAccessToken(eq(actor), anyList(), anyString())).thenReturn("accessSecretD05");
    audit = new SecurityAuditLog();
    auth = authWith(audit);
    probe.secret("passwordHashD05");
    probe.secret("accessSecretD05");
  }

  private AuthService authWith(SecurityAuditLog log) {
    return new AuthService(
        users,
        refresh,
        verification,
        encoder,
        jwt,
        new JwtProperties(),
        guard,
        mock(EmailNotificationPublisher.class),
        redis,
        new SystemAdminProperties(),
        revoker,
        clock,
        mock(PasswordResetMailBudget.class),
        log);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    TenantContext.clear();
    try {
      probe.noSecrets();
    } finally {
      probe.close();
    }
  }

  private AuthService.LoginResult acceptedLogin() {
    when(encoder.matches(password, user.getPasswordHash())).thenReturn(true);
    var result = auth.login(email, password, ip);
    assertEquals("accessSecretD05", result.accessToken());
    assertNotNull(result.refreshToken());
    probe.secret(result.refreshToken());
    return result;
  }

  @Test
  void K1_R1_R2_badCredentialsHasExactlyOneCompleteLineAndSuccessIsSilent() {
    assertThrows(BusinessRuleException.class, () -> auth.login(email, password, ip));
    probe.exact("LOGIN_FAILED", actor, Map.of("ip", ip, "reason", "bad_credentials"));
    assertEquals(1, probe.rows().size());
    probe.clear();
    acceptedLogin();
    assertTrue(probe.rows().isEmpty());
  }

  @Test
  void K2_R2_unknownAccountUsesDashWithoutEmailLocalPart() {
    when(users.findByEmail(email)).thenReturn(Optional.empty());
    assertThrows(BusinessRuleException.class, () -> auth.login(email, password, ip));
    probe.exact("LOGIN_FAILED", null, Map.of("ip", ip, "reason", "bad_credentials"));
    when(users.findByEmail(email)).thenReturn(Optional.of(user));
    acceptedLogin();
  }

  @ParameterizedTest
  @ValueSource(strings = {"locked", "throttled"})
  void K3_R1_R2_blockedLoginHasActorAndReasonWithoutAdditionalLockEvent(String reason) {
    when(guard.checkBeforeAttempt(email, ip))
        .thenReturn(
            new BruteForceGuard.CheckResult(
                reason.equals("locked"), reason.equals("throttled"), false));
    assertThrows(BusinessRuleException.class, () -> auth.login(email, password, ip));
    probe.exact("LOGIN_FAILED", actor, Map.of("ip", ip, "reason", reason));
    assertEquals(1, probe.rows().size());
    when(guard.checkBeforeAttempt(email, ip))
        .thenReturn(new BruteForceGuard.CheckResult(false, false, false));
    probe.clear();
    acceptedLogin();
    assertTrue(probe.rows().isEmpty());
  }

  @Test
  void K3_R1_lockTransitionWritesOnceAndLaterAttemptsDoNotRepeatIt() {
    BruteForceGuard realGuard = new BruteForceGuard(redis, audit);
    long threshold =
        (Long) ReflectionTestUtils.getField(BruteForceGuard.class, "USER_LOCK_THRESHOLD");
    when(values.increment("login_fail:user:" + email))
        .thenReturn(threshold - 1, threshold, threshold + 1);
    when(values.increment("login_fail:ip:" + ip)).thenReturn(1L);
    realGuard.recordFailure(email, ip, actor);
    assertTrue(probe.rows().isEmpty(), "below configured threshold");
    realGuard.recordFailure(email, ip, actor);
    realGuard.recordFailure(email, ip, actor);
    probe.exact("ACCOUNT_LOCKED", actor, Map.of("ip", ip));
    assertEquals(1, probe.rows().size());
    when(redis.hasKey("login_lock:user:" + email)).thenReturn(true);
    assertTrue(realGuard.checkBeforeAttempt(email, ip).locked());
    AuthService guarded =
        new AuthService(
            users,
            refresh,
            verification,
            encoder,
            jwt,
            new JwtProperties(),
            realGuard,
            mock(EmailNotificationPublisher.class),
            redis,
            new SystemAdminProperties(),
            revoker,
            clock,
            mock(PasswordResetMailBudget.class),
            audit);
    assertThrows(BusinessRuleException.class, () -> guarded.login(email, password, ip));
    assertThrows(BusinessRuleException.class, () -> guarded.login(email, password, ip));
    assertEquals(1, probe.events("ACCOUNT_LOCKED").size());
    assertEquals(2, probe.events("LOGIN_FAILED").size());
    for (var row : probe.events("LOGIN_FAILED")) {
      assertTrue(row.getFormattedMessage().endsWith(" reason=locked"));
    }
    probe.clear();
    acceptedLogin();
    assertTrue(probe.rows().isEmpty());
  }

  private RefreshToken token(String raw, Instant usedAt) {
    probe.secret(raw);
    RefreshToken token =
        RefreshToken.issue(
            UUID.randomUUID(),
            actor,
            UUID.randomUUID(),
            TokenHasher.sha256Hex(raw),
            "jti-D05",
            now.plusSeconds(3600));
    if (usedAt != null) token.markUsed(usedAt);
    when(refresh.findByTokenHash(TokenHasher.sha256Hex(raw))).thenReturn(Optional.of(token));
    return token;
  }

  @Test
  void K4_R1_R2_reuseAuditsAfterSuccessfulRevokeAndGraceIsSilent() {
    String raw = "refresh-" + UUID.randomUUID();
    RefreshToken token = token(raw, now.minusSeconds(120));
    doAnswer(
            invocation -> {
              assertTrue(
                  probe.events("REFRESH_TOKEN_REUSE").isEmpty(), "audit must follow revocation");
              return null;
            })
        .when(revoker)
        .revokeFamily(token.getFamilyId());
    assertThrows(RefreshTokenReuseException.class, () -> auth.refresh(raw));
    verify(revoker).revokeFamily(token.getFamilyId());
    probe.exact("REFRESH_TOKEN_REUSE", actor, Map.of("family", token.getFamilyId().toString()));
    probe.clear();
    String grace = "grace-" + UUID.randomUUID();
    token(grace, Instant.now());
    assertNotNull(auth.refresh(grace).accessToken());
    assertTrue(probe.rows().isEmpty());
  }

  @Test
  void K4_R1_revocationFailureDoesNotClaimSuccessfulReuseHandling() {
    String raw = "refresh-fail-" + UUID.randomUUID();
    RefreshToken token = token(raw, now.minusSeconds(120));
    doThrow(new IllegalStateException("revoke failed"))
        .when(revoker)
        .revokeFamily(token.getFamilyId());
    assertThrows(IllegalStateException.class, () -> auth.refresh(raw));
    assertTrue(probe.rows().isEmpty());
    probe.clear();
    String fresh = "fresh-" + actor;
    token(fresh, null);
    assertNotNull(auth.refresh(fresh).accessToken());
    assertTrue(probe.rows().isEmpty());
  }

  private void authenticated() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    TenantContext.setWorkspaceId(workspace);
  }

  @Test
  void K5_R2_handler403LogsMethodPathAndWorkspaceWithoutQueryOrAuthorization() {
    authenticated();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects");
    String query = "secretQuery=" + UUID.randomUUID();
    request.setQueryString(query);
    request.addHeader("Authorization", "Bearer accessSecretD05");
    probe.secret(query);
    var response =
        new GlobalExceptionHandler(null, audit)
            .handleAccessDenied(new AccessDeniedException("denied"), request);
    assertEquals(403, response.getStatus());
    probe.exact(
        "ACCESS_DENIED",
        actor,
        Map.of("method", "POST", "path", "/api/v1/projects", "workspace", workspace.toString()));
  }

  @Test
  void K5_R1_R2_filter403HasAuditAndMemberRequestIsSilent() throws Exception {
    authenticated();
    WorkspaceMembershipService membership = mock(WorkspaceMembershipService.class);
    WorkspaceContextFilter filter = new WorkspaceContextFilter(membership, audit);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/workspaces/members");
    request.addHeader("X-Workspace-Id", workspace.toString());
    request.setQueryString("privateQueryD05");
    probe.secret("privateQueryD05");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    filter.doFilter(request, response, chain);
    assertEquals(403, response.getStatus());
    verifyNoInteractions(chain);
    probe.exact(
        "ACCESS_DENIED",
        actor,
        Map.of(
            "method",
            "GET",
            "path",
            "/api/v1/workspaces/members",
            "workspace",
            workspace.toString()));
    probe.clear();
    when(membership.findRole(actor, workspace)).thenReturn(Optional.of(WorkspaceRole.VIEWER));
    MockHttpServletResponse accepted = new MockHttpServletResponse();
    filter.doFilter(request, accepted, chain);
    assertEquals(200, accepted.getStatus());
    verify(chain).doFilter(request, accepted);
    assertTrue(probe.rows().isEmpty());
  }

  @Test
  void K6_R1_R2_patEventsCarryIdsAndNoSecrets() {
    var repository = mock(PersonalAccessTokenRepository.class);
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    var service = new PersonalAccessTokenService(repository, audit);
    var issued = service.create(actor, "pat-name-secret-D05", Duration.ofDays(1));
    probe.secret(issued.rawToken());
    probe.secret("pat-name-secret-D05");
    assertNotNull(issued.entity());
    UUID id = issued.entity().getId();
    probe.exact("PAT_CREATED", actor, Map.of("token", id.toString()));
    when(repository.findById(id)).thenReturn(Optional.of(issued.entity()));
    service.revoke(actor, id);
    probe.exact("PAT_REVOKED", actor, Map.of("token", id.toString()));
    probe.clear();
    service.list(actor);
    assertTrue(probe.rows().isEmpty());
  }

  @Test
  void K6_R1_R2_membershipEventsHaveActorTargetAndOnlyNewRole() {
    authenticated();
    UUID target = UUID.randomUUID();
    User targetUser = User.newUser(target, email, "passwordHashD05", "Target user");
    when(users.findByEmail(email)).thenReturn(Optional.of(targetUser));
    when(users.findById(target)).thenReturn(Optional.of(targetUser));
    var members = mock(WorkspaceUserRepository.class);
    var service = new WorkspaceMemberService(members, users, audit);
    assertEquals(target, service.addMember(email, WorkspaceRole.VIEWER).userId());
    probe.exact(
        "MEMBER_ADDED",
        actor,
        Map.of(
            "workspace",
            workspace.toString(),
            "target",
            target.toString(),
            "role",
            WorkspaceRole.VIEWER));
    var member = WorkspaceUser.of(workspace, target, WorkspaceRole.VIEWER);
    when(members.findByWorkspaceIdAndUserId(workspace, target)).thenReturn(Optional.of(member));
    service.changeRole(target, WorkspaceRole.DEVELOPER);
    probe.exact(
        "MEMBER_ROLE_CHANGED",
        actor,
        Map.of(
            "workspace",
            workspace.toString(),
            "target",
            target.toString(),
            "role",
            WorkspaceRole.DEVELOPER));
    service.removeMember(target);
    probe.exact(
        "MEMBER_REMOVED",
        actor,
        Map.of("workspace", workspace.toString(), "target", target.toString()));
    verify(members).delete(member);
    probe.clear();
    service.listMembers();
    assertTrue(probe.rows().isEmpty());
  }

  @Test
  void K7_R2_resetCompletionIsAuditedAndNewPasswordIsUsable() {
    String raw = "reset-" + UUID.randomUUID();
    probe.secret(raw);
    when(verification.findByTokenHash(TokenHasher.sha256Hex(raw)))
        .thenReturn(
            Optional.of(
                VerificationToken.issue(
                    UUID.randomUUID(),
                    actor,
                    TokenHasher.sha256Hex(raw),
                    "PASSWORD_RESET",
                    now.plusSeconds(600))));
    auth.confirmPasswordReset(raw, password);
    probe.exact("PASSWORD_RESET_COMPLETED", actor, Map.of());
    verify(users).save(user);
    probe.clear();
    acceptedLogin();
    assertTrue(probe.rows().isEmpty());
  }

  @Test
  void R1_successfulRefreshIsSilent() {
    String raw = "fresh-" + UUID.randomUUID();
    token(raw, null);
    assertNotNull(auth.refresh(raw).accessToken());
    assertTrue(probe.rows().isEmpty());
  }

  @Test
  void K7_R1_invalidResetIsSilentAndValidResetCompletes() {
    String raw = "unknown-reset-" + UUID.randomUUID();
    assertThrows(BusinessRuleException.class, () -> auth.confirmPasswordReset(raw, password));
    assertTrue(probe.rows().isEmpty());
    when(verification.findByTokenHash(TokenHasher.sha256Hex(raw)))
        .thenReturn(
            Optional.of(
                VerificationToken.issue(
                    UUID.randomUUID(),
                    actor,
                    TokenHasher.sha256Hex(raw),
                    "PASSWORD_RESET",
                    now.plusSeconds(600))));
    auth.confirmPasswordReset(raw, password);
    probe.exact("PASSWORD_RESET_COMPLETED", actor, Map.of());
  }

  @Test
  void K6_R1_failedPersistenceDoesNotLogSuccessfulPatCreation() {
    var repository = mock(PersonalAccessTokenRepository.class);
    when(repository.save(any())).thenThrow(new IllegalStateException("database failed"));
    var service = new PersonalAccessTokenService(repository, audit);
    assertThrows(
        IllegalStateException.class, () -> service.create(actor, "test PAT", Duration.ofDays(1)));
    assertTrue(probe.rows().isEmpty());
    doAnswer(i -> i.getArgument(0)).when(repository).save(any());
    var issued = service.create(actor, "test PAT", Duration.ofDays(1));
    probe.secret(issued.rawToken());
    probe.exact("PAT_CREATED", actor, Map.of("token", issued.entity().getId().toString()));
  }

  @ParameterizedTest
  @ValueSource(ints = {99, 100, 101})
  void R3_valueLengthBoundaryAndValidCharactersArePreserved(int length) {
    String value = "a".repeat(length);
    audit.record(SecurityAuditEvent.ACCESS_DENIED, actor, Map.of("path", value));
    probe.exact("ACCESS_DENIED", actor, Map.of("path", "a".repeat(Math.min(length, 100))));
  }

  @Test
  void R3_K1_valuesAreSanitizedTruncatedAndAlphabeticalInTextAndStructuredFields() {
    var fields = new LinkedHashMap<String, String>();
    fields.put("reason", "bad_credentials");
    fields.put("ip", "192.0.2.1\r\n forged=x\t+?%ü" + "z".repeat(130));
    audit.record(SecurityAuditEvent.LOGIN_FAILED, actor, fields);
    String sanitized = "192.0.2.1___forged_x_____" + "z".repeat(130);
    probe.exact(
        "LOGIN_FAILED",
        actor,
        Map.of("ip", sanitized.substring(0, 100), "reason", "bad_credentials"));
    probe.clear();
    audit.record(
        SecurityAuditEvent.LOGIN_FAILED, actor, Map.of("ip", "AZaz09._:@/-", "reason", "locked"));
    probe.exact("LOGIN_FAILED", actor, Map.of("ip", "AZaz09._:@/-", "reason", "locked"));
  }

  @Test
  void R4_K1_auditBeanFailurePreservesBusinessExceptionAndSuccessfulLogin() {
    BusinessRuleException baseline =
        assertThrows(BusinessRuleException.class, () -> auth.login(email, password, ip));
    var broken = mock(SecurityAuditLog.class);
    doThrow(new IllegalStateException("audit down")).when(broken).record(any(), any(), anyMap());
    var failing = authWith(broken);
    BusinessRuleException actual =
        assertThrows(BusinessRuleException.class, () -> failing.login(email, password, ip));
    assertEquals(baseline.getMessage(), actual.getMessage());
    verify(broken).record(eq(SecurityAuditEvent.LOGIN_FAILED), eq(actor), anyMap());
    when(encoder.matches(password, user.getPasswordHash())).thenReturn(true);
    assertEquals("accessSecretD05", failing.login(email, password, ip).accessToken());
  }

  @Test
  void R4_K5_filterAuditFailurePreservesExact403BodyAndDoesNotInvokeChain() throws Exception {
    authenticated();
    var membership = mock(WorkspaceMembershipService.class);
    var broken = mock(SecurityAuditLog.class);
    doThrow(new IllegalStateException("audit down")).when(broken).record(any(), any(), anyMap());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
    request.addHeader("X-Workspace-Id", workspace.toString());
    MockHttpServletResponse normal = new MockHttpServletResponse();
    MockHttpServletResponse failedAudit = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    new WorkspaceContextFilter(membership, audit).doFilter(request, normal, chain);
    new WorkspaceContextFilter(membership, broken).doFilter(request, failedAudit, chain);
    assertEquals(403, normal.getStatus());
    assertEquals(normal.getStatus(), failedAudit.getStatus());
    assertEquals(normal.getContentAsString(), failedAudit.getContentAsString());
    assertEquals(normal.getContentType(), failedAudit.getContentType());
    verifyNoInteractions(chain);
  }

  @Test
  void R4_recordInternalFailureIsContainedAndValidRecordStillWorks() {
    Map<String, String> exploding = mock(Map.class);
    when(exploding.entrySet()).thenThrow(new IllegalStateException("attribute processing failed"));
    assertDoesNotThrow(() -> audit.record(SecurityAuditEvent.LOGIN_FAILED, actor, exploding));
    assertTrue(probe.rows().isEmpty());
    audit.record(SecurityAuditEvent.LOGIN_FAILED, actor, Map.of("ip", ip, "reason", "locked"));
    probe.exact("LOGIN_FAILED", actor, Map.of("ip", ip, "reason", "locked"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"active", "expired", "revoked"})
  void R1_R2_wsDeliveryAuditsOnlyErrorTermination(String status) {
    var membership = mock(WorkspaceMembershipService.class);
    var registry = new WebSocketSessionRegistry();
    String session = "audit-ws-" + actor;
    String raw = "ws-access-" + UUID.randomUUID();
    probe.secret(raw);
    Instant expiry = status.equals("expired") ? now.minusSeconds(1) : now.plusSeconds(600);
    when(jwt.verify(raw)).thenReturn(new DecodedJwt(actor, List.of(), "jti-D05", expiry));
    var inbound = new WebSocketAuthInterceptor(jwt, redis, membership, registry, clock);
    var connect = StompHeaderAccessor.create(StompCommand.CONNECT);
    connect.setSessionId(session);
    connect.setNativeHeader("Authorization", "Bearer " + raw);
    connect.setLeaveMutable(true);
    Message<byte[]> connection =
        MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders());
    assertSame(connection, inbound.preSend(connection, null));
    if (status.equals("revoked")) when(redis.hasKey("jwt:blacklist:jti-D05")).thenReturn(true);
    var headers = StompHeaderAccessor.create(StompCommand.MESSAGE);
    headers.setSessionId(session);
    headers.setDestination("/queue/notifications");
    Message<byte[]> outbound =
        MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    var delivery = new WebSocketDeliveryGuard(registry, clock, redis, membership, audit);
    Message<?> result = delivery.preSend(outbound, null);
    if (status.equals("active")) {
      assertSame(outbound, result);
      assertTrue(probe.rows().isEmpty());
    } else {
      assertNotNull(result);
      var error = StompHeaderAccessor.wrap(result);
      assertEquals(StompCommand.ERROR, error.getCommand());
      assertEquals(
          status.equals("expired") ? "Oturum suresi doldu" : "Yetki geri alindi",
          error.getMessage());
      probe.exact("WS_SESSION_TERMINATED", actor, Map.of("reason", status));
      assertNull(delivery.preSend(outbound, null));
      assertEquals(1, probe.events("WS_SESSION_TERMINATED").size());
    }
  }

  @Test
  void R4_springUsesInjectedAuditBeanForAllOverloadedConstructors() {
    List<Class<?>> services =
        List.of(
            AuthService.class,
            BruteForceGuard.class,
            GlobalExceptionHandler.class,
            WorkspaceContextFilter.class,
            PersonalAccessTokenService.class,
            WorkspaceMemberService.class,
            WebSocketDeliveryGuard.class);
    try (var spring = new AnnotationConfigApplicationContext()) {
      SecurityAuditLog sentinel = mock(SecurityAuditLog.class);
      spring.registerBean(SecurityAuditLog.class, () -> sentinel);
      Set<Class<?>> dependencies = new HashSet<>();
      for (Class<?> service : services) {
        Constructor<?>[] constructors = service.getDeclaredConstructors();
        Constructor<?> selected =
            Arrays.stream(constructors)
                .filter(c -> c.isAnnotationPresent(Autowired.class))
                .findFirst()
                .orElse(constructors[0]);
        for (Class<?> type : selected.getParameterTypes()) {
          if (!services.contains(type) && type != SecurityAuditLog.class) dependencies.add(type);
        }
        spring.registerBean(service);
      }
      for (Class<?> type : dependencies) registerMock(spring, type);
      spring.refresh();
      for (Class<?> service : services) {
        Object bean = spring.getBean(service);
        Object target = AopProxyUtils.getSingletonTarget(bean);
        assertSame(
            sentinel,
            ReflectionTestUtils.getField(target == null ? bean : target, "auditLog"),
            service.getName());
      }
    }
  }

  private static <T> void registerMock(AnnotationConfigApplicationContext context, Class<T> type) {
    context.registerBean(type, () -> mock(type));
  }
}

/** Captures this test's identities only; checks both human-readable and structured records. */
final class AuditContractProbe implements AutoCloseable {
  private final Logger logger = (Logger) LoggerFactory.getLogger("security.audit");
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Set<String> identities = new HashSet<>();
  private final Set<String> secrets = new HashSet<>();

  AuditContractProbe() {
    appender.start();
    logger.addAppender(appender);
  }

  void watch(Object identity) {
    identities.add(identity.toString());
  }

  void secret(String secret) {
    assertNotNull(secret);
    assertFalse(secret.isBlank());
    secrets.add(secret);
    secrets.add(TokenHasher.sha256Hex(secret));
    if (secret.contains("@")) secrets.add(secret.substring(0, secret.indexOf('@')));
  }

  List<ILoggingEvent> rows() {
    synchronized (appender) {
      return appender.list.stream()
          .filter(e -> identities.stream().anyMatch(e.getFormattedMessage()::contains))
          .toList();
    }
  }

  List<ILoggingEvent> events(String event) {
    return rows().stream()
        .filter(e -> e.getFormattedMessage().startsWith("security_audit event=" + event + " "))
        .toList();
  }

  void exact(String event, UUID actor, Map<String, String> attributes) {
    List<ILoggingEvent> matches = events(event);
    assertEquals(1, matches.size(), () -> "Expected one " + event + ": " + rows());
    ILoggingEvent row = matches.get(0);
    String actorText = actor == null ? "-" : actor.toString();
    String expected = "security_audit event=" + event + " actor=" + actorText;
    for (var entry : new TreeMap<>(attributes).entrySet())
      expected += " " + entry.getKey() + "=" + entry.getValue();
    assertEquals("security.audit", row.getLoggerName());
    assertEquals(Level.INFO, row.getLevel());
    assertEquals(expected, row.getFormattedMessage());
    assertNotNull(row.getKeyValuePairs());
    Map<String, String> structured =
        row.getKeyValuePairs().stream()
            .collect(Collectors.toMap(kv -> kv.key, kv -> String.valueOf(kv.value)));
    Map<String, String> expectedFields = new HashMap<>(attributes);
    expectedFields.put("event", event);
    expectedFields.put("actor", actorText);
    assertEquals(expectedFields, structured);
    assertEquals(attributes.size() + 2, row.getKeyValuePairs().size());
  }

  void noSecrets() {
    for (var row : rows()) {
      String text = row.getFormattedMessage() + " " + row.getKeyValuePairs();
      for (String secret : secrets)
        assertFalse(text.contains(secret), "R2: audit disclosed a secret");
      assertFalse(text.contains("Authorization"));
      assertFalse(text.contains("Bearer "));
      assertFalse(text.contains("?"));
      assertFalse(text.contains("\r"));
      assertFalse(text.contains("\n"));
    }
  }

  void clear() {
    noSecrets();
    synchronized (appender) {
      appender.list.clear();
    }
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
  }
}
