package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.app.tracker.accesstoken.service.PersonalAccessTokenService;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.notification.EmailNotificationPublisher;
import com.app.tracker.core.ratelimit.RateLimiter;
import com.app.tracker.core.security.*;
import com.app.tracker.core.security.model.VerificationToken;
import com.app.tracker.core.security.repository.RefreshTokenRepository;
import com.app.tracker.core.security.repository.VerificationTokenRepository;
import com.app.tracker.security.support.MailpitInbox;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.user.controller.MeController;
import com.app.tracker.user.dto.MeResponse;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import com.app.tracker.user.service.MeService;
import com.app.tracker.workspace.model.Workspace;
import com.app.tracker.workspace.model.WorkspaceInvitation;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceInvitationRepository;
import com.app.tracker.workspace.repository.WorkspaceRepository;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import com.app.tracker.workspace.service.WorkspaceInvitationService;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Independent expectations: dilim-02-sozlesme.md K1-K4/R1-R4. R5 is a source review only. Run this
 * class for HTTP/DB/Mailpit; run InvitationVerificationServiceContractTest for Docker-free mutation
 * checks. Both classes deliberately use flat tests for accurate Surefire counts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvitationVerificationContractTest extends AbstractIntegrationTest {
  private static final String RESEND = "/api/v1/me/verification-email";
  private static final String NEED_VERIFICATION =
      "Daveti kabul etmek icin once e-posta adresini dogrula.";
  @LocalServerPort private int port;
  @Autowired private ApplicationContext context;
  @Autowired private ObjectMapper mapper;
  @Autowired private PersonalAccessTokenService pats;
  @Autowired private StringRedisTemplate redis;
  private SecurityHttp http;
  private MailpitInbox inbox;

  @BeforeEach
  void setUp() {
    http = new SecurityHttp(port);
    inbox = new MailpitInbox(InvitationVerificationContractTest::mailpitApiUrl, mapper);
  }

  @Test
  void K1_verifiedCanonicalRecipientAcceptsAndCreatesMembership() throws Exception {
    Tenant tenant = SecurityFixture.create(context, port).a();
    Actor actor = register();
    execute("UPDATE users SET email_verified=true WHERE id=?", actor.userId());
    String token = invite(tenant, actor.email().toUpperCase(Locale.ROOT));
    assertAccepted(tenant, token, actor);
  }

  @Test
  void K2_K3_R1_unverifiedRejectionPreservesInvitationThenNewMailVerifiesAndAccepts()
      throws Exception {
    Tenant tenant = SecurityFixture.create(context, port).a();
    Actor actor = register();
    String token = invite(tenant, actor.email());
    assertMeVerified(actor, false);
    HttpResponse<String> rejected = accept(token, actor);
    assertEquals(400, rejected.statusCode(), rejected.body());
    assertEquals(NEED_VERIFICATION, mapper.readTree(rejected.body()).path("detail").asString());
    assertEquals("0", membershipCount(tenant, actor));
    assertEquals("PENDING", invitationStatus(token));
    Set<String> oldIds = verificationMessageIds(actor.email());
    long tokensBefore = tokenCount(actor);
    assertAcceptedEmpty(http.post(RESEND, actor, null, null));
    waitUntil(() -> inbox.count(actor.email(), "dogrula") == oldIds.size() + 1);
    assertEquals(tokensBefore + 1, tokenCount(actor));
    String raw = newVerificationToken(actor.email(), oldIds);
    assertEquals(
        "1",
        scalar(
            "SELECT count(*) FROM verification_tokens WHERE user_id=? AND token_hash=? AND purpose='EMAIL_VERIFICATION'",
            actor.userId(),
            TokenHasher.sha256Hex(raw)));
    HttpResponse<String> verified =
        http.get(
            "/api/v1/auth/verify-email?token=" + URLEncoder.encode(raw, StandardCharsets.UTF_8),
            (String) null,
            null,
            null);
    assertTrue(verified.statusCode() >= 200 && verified.statusCode() < 300, verified.body());
    assertEquals(
        "true", scalar("SELECT email_verified::text FROM users WHERE id=?", actor.userId()));
    assertMeVerified(actor, true);
    assertAccepted(tenant, token, actor);
  }

  @Test
  void K4_verifiedAccountReturns202WithoutMailTokenOrBudgetConsumption() throws Exception {
    Actor actor = register();
    execute("UPDATE users SET email_verified=true WHERE id=?", actor.userId());
    long tokensBefore = tokenCount(actor);
    int mailBefore = inbox.count(actor.email(), "dogrula");
    for (int i = 0; i < 4; i++) {
      assertAcceptedEmpty(http.post(RESEND, actor, null, null));
    }
    assertEquals(tokensBefore, tokenCount(actor));
    assertNull(redis.opsForValue().get("verify_mail:" + actor.userId()));
    assertMailCountRemains(actor.email(), mailBefore);
    // Acceptance twin: the same authenticated endpoint works after reverting only verification.
    execute("UPDATE users SET email_verified=false WHERE id=?", actor.userId());
    assertAcceptedEmpty(http.post(RESEND, actor, null, null));
    waitUntil(() -> inbox.count(actor.email(), "dogrula") == mailBefore + 1);
    assertEquals(tokensBefore + 1, tokenCount(actor));
  }

  @ParameterizedTest(name = "R2 precedence: {0}")
  @ValueSource(strings = {"invalid", "expired", "revoked", "mismatch"})
  void R2_existingErrorsPrecedeVerificationAndValidVerifiedTwinAccepts(String scenario)
      throws Exception {
    Tenant tenant = SecurityFixture.create(context, port).a();
    Actor actor = register();
    String original = invite(tenant, actor.email());
    String tested = original;
    if (scenario.equals("invalid")) {
      tested = "unknown-" + UUID.randomUUID();
    } else if (scenario.equals("expired")) {
      execute(
          "UPDATE workspace_invitations SET expires_at=now()-interval '1 day' WHERE token_hash=?",
          TokenHasher.sha256Hex(original));
    } else if (scenario.equals("revoked")) {
      execute(
          "UPDATE workspace_invitations SET status='REVOKED' WHERE token_hash=?",
          TokenHasher.sha256Hex(original));
    } else {
      execute(
          "UPDATE workspace_invitations SET email=? WHERE token_hash=?",
          "other-" + UUID.randomUUID() + "@x.test",
          TokenHasher.sha256Hex(original));
    }
    // R2 specifies unchanged existing errors, not literal details for invalid tokens.
    // Compare the verified and unverified account at the same invalid-input boundary.
    execute("UPDATE users SET email_verified=true WHERE id=?", actor.userId());
    HttpResponse<String> existingError = accept(tested, actor);
    assertEquals(400, existingError.statusCode(), existingError.body());
    String expected = mapper.readTree(existingError.body()).path("detail").asString();
    assertFalse(expected.isBlank(), existingError.body());
    assertNotEquals(NEED_VERIFICATION, expected);
    execute("UPDATE users SET email_verified=false WHERE id=?", actor.userId());
    HttpResponse<String> rejected = accept(tested, actor);
    assertEquals(400, rejected.statusCode(), rejected.body());
    String detail = mapper.readTree(rejected.body()).path("detail").asString();
    if (scenario.equals("mismatch")) {
      assertTrue(detail.contains("farkli bir e-posta adresine"), detail);
    }
    assertEquals(expected, detail);
    assertEquals("0", membershipCount(tenant, actor));
    assertEquals(scenario.equals("revoked") ? "REVOKED" : "PENDING", invitationStatus(original));
    execute(
        "UPDATE workspace_invitations SET status='PENDING', expires_at=now()+interval '1 day', email=? WHERE token_hash=?",
        actor.email(),
        TokenHasher.sha256Hex(original));
    execute("UPDATE users SET email_verified=true WHERE id=?", actor.userId());
    assertAccepted(tenant, original, actor);
  }

  @Test
  void R3_threeMailsFourthSuppressedAndPasswordResetAndOtherUserHaveSeparateBudgets()
      throws Exception {
    Actor actor = register();
    int before = inbox.count(actor.email(), "dogrula");
    long tokensBefore = tokenCount(actor);
    // Exhausting the reset budget must not spend any verification budget.
    for (int i = 0; i < 3; i++) {
      HttpResponse<String> reset =
          anonymousPost("/api/v1/auth/password-reset/request", Map.of("email", actor.email()));
      assertEquals(202, reset.statusCode(), reset.body());
    }
    for (int i = 1; i <= 3; i++) {
      assertAcceptedEmpty(http.post(RESEND, actor, null, null));
      int expected = before + i;
      waitUntil(() -> inbox.count(actor.email(), "dogrula") == expected);
      assertEquals(tokensBefore + i, tokenCount(actor));
    }
    assertEquals("3", redis.opsForValue().get("pwreset_mail:" + actor.userId()));
    assertEquals("3", redis.opsForValue().get("verify_mail:" + actor.userId()));
    Long ttl = redis.getExpire("verify_mail:" + actor.userId());
    assertNotNull(ttl);
    assertTrue(ttl > 0 && ttl <= 900, "verification window must be 15 minutes: " + ttl);
    assertAcceptedEmpty(http.post(RESEND, actor, null, null));
    assertEquals(tokensBefore + 3, tokenCount(actor));
    assertMailCountRemains(actor.email(), before + 3);
    Actor other = register();
    assertAcceptedEmpty(http.post(RESEND, other, null, null));
    waitUntil(() -> inbox.count(other.email(), "dogrula") == 2);
    assertEquals("1", redis.opsForValue().get("verify_mail:" + other.userId()));
  }

  @Test
  void R4_anonymous401AndValidPat403WhileBodylessJwtWithoutWorkspaceGets202() throws Exception {
    Actor actor = register();
    long before = tokenCount(actor);
    assertEquals(401, http.post(RESEND, (String) null, null, null).statusCode());
    String pat = pats.create(actor.userId(), "dilim02", null).rawToken();
    HttpResponse<String> patMe = http.get("/api/v1/me", pat, null, null);
    assertEquals(200, patMe.statusCode(), patMe.body());
    assertEquals(actor.userId().toString(), mapper.readTree(patMe.body()).path("id").asString());
    assertEquals(403, http.post(RESEND, pat, null, null).statusCode());
    assertEquals(before, tokenCount(actor));
    assertMailCountRemains(actor.email(), 1);
    assertAcceptedEmpty(http.post(RESEND, actor, null, null));
    waitUntil(() -> inbox.count(actor.email(), "dogrula") == 2);
    assertEquals(before + 1, tokenCount(actor));
  }

  private Actor register() throws Exception {
    String email = "dilim02-" + UUID.randomUUID() + "@x.test";
    HttpResponse<String> registered =
        anonymousPost(
            "/api/v1/auth/register",
            Map.of(
                "email",
                email,
                "password",
                SecurityFixture.PASSWORD,
                "fullName",
                "Contract",
                "privacyNoticeAccepted",
                true,
                "privacyNoticeVersion",
                PrivacyNotice.CURRENT_VERSION));
    assertEquals(201, registered.statusCode(), registered.body());
    HttpResponse<String> loggedIn =
        anonymousPost(
            "/api/v1/auth/login", Map.of("email", email, "password", SecurityFixture.PASSWORD));
    assertEquals(200, loggedIn.statusCode(), loggedIn.body());
    Actor actor =
        new Actor(
            UUID.fromString(scalar("SELECT id FROM users WHERE email=?", email)),
            email,
            mapper.readTree(loggedIn.body()).path("accessToken").asString());
    waitUntil(() -> inbox.count(email, "dogrula") == 1);
    return actor;
  }

  private HttpResponse<String> anonymousPost(String path, Map<String, ?> body) {
    return http.send(
        "POST",
        path,
        null,
        null,
        mapper.writeValueAsString(body),
        Map.of(
            "X-Forwarded-For",
            "10."
                + (1 + new Random().nextInt(254))
                + "."
                + (1 + new Random().nextInt(254))
                + "."
                + (1 + new Random().nextInt(254))));
  }

  private String invite(Tenant tenant, String email) {
    HttpResponse<String> created =
        http.post(
            "/api/v1/workspaces/members/invitations",
            tenant.admin(),
            tenant.workspaceId(),
            mapper.writeValueAsString(Map.of("email", email, "role", WorkspaceRole.VIEWER)));
    assertEquals(201, created.statusCode(), created.body());
    return inbox.inviteToken(email.toLowerCase(Locale.ROOT));
  }

  private HttpResponse<String> accept(String token, Actor actor) {
    return http.post("/api/v1/invitations/" + token + "/accept", actor, null, null);
  }

  private void assertAccepted(Tenant tenant, String token, Actor actor) throws Exception {
    HttpResponse<String> response = accept(token, actor);
    assertEquals(200, response.statusCode(), response.body());
    JsonNode json = mapper.readTree(response.body());
    assertEquals(tenant.workspaceId().toString(), json.path("workspaceId").asString());
    assertFalse(json.path("workspaceName").asString("").isBlank(), response.body());
    assertEquals(WorkspaceRole.VIEWER, json.path("role").asString());
    assertEquals("1", membershipCount(tenant, actor));
    assertEquals(
        WorkspaceRole.VIEWER,
        scalar(
            "SELECT role FROM workspace_users WHERE workspace_id=? AND user_id=?",
            tenant.workspaceId(),
            actor.userId()));
    assertEquals("ACCEPTED", invitationStatus(token));
  }

  private void assertMeVerified(Actor actor, boolean expected) {
    HttpResponse<String> me = http.get("/api/v1/me", actor, null, null);
    assertEquals(200, me.statusCode(), me.body());
    JsonNode field = mapper.readTree(me.body()).path("emailVerified");
    assertTrue(field.isBoolean(), me.body());
    assertEquals(expected, field.asBoolean());
  }

  private static void assertAcceptedEmpty(HttpResponse<String> response) {
    assertEquals(202, response.statusCode(), response.body());
    assertEquals("", response.body());
  }

  private long tokenCount(Actor actor) throws Exception {
    return Long.parseLong(
        scalar(
            "SELECT count(*) FROM verification_tokens WHERE user_id=? AND purpose='EMAIL_VERIFICATION'",
            actor.userId()));
  }

  private String membershipCount(Tenant tenant, Actor actor) throws Exception {
    return scalar(
        "SELECT count(*) FROM workspace_users WHERE workspace_id=? AND user_id=?",
        tenant.workspaceId(),
        actor.userId());
  }

  private String invitationStatus(String token) throws Exception {
    return scalar(
        "SELECT status FROM workspace_invitations WHERE token_hash=?",
        TokenHasher.sha256Hex(token));
  }

  private Set<String> verificationMessageIds(String email) throws Exception {
    Set<String> ids = new HashSet<>();
    for (JsonNode message : verificationMessages(email)) {
      ids.add(message.path("ID").asString());
    }
    return ids;
  }

  private List<JsonNode> verificationMessages(String email) throws Exception {
    JsonNode root =
        mapper.readTree(
            mailGet(
                "/api/v1/search?limit=500&query="
                    + URLEncoder.encode("to:" + email, StandardCharsets.UTF_8)));
    List<JsonNode> messages = new ArrayList<>();
    for (JsonNode message : root.path("messages")) {
      boolean recipient = false;
      for (JsonNode to : message.path("To")) {
        recipient |= email.equalsIgnoreCase(to.path("Address").asString());
      }
      if (recipient && message.path("Subject").asString().contains("dogrula")) {
        messages.add(message);
      }
    }
    return messages;
  }

  private String newVerificationToken(String email, Set<String> oldIds) throws Exception {
    for (JsonNode message : verificationMessages(email)) {
      String id = message.path("ID").asString();
      if (!oldIds.contains(id)) {
        String text = mapper.readTree(mailGet("/api/v1/message/" + id)).path("Text").asString();
        Matcher matcher = Pattern.compile("token=([^\\s\"<&]+)").matcher(text);
        assertTrue(matcher.find(), text);
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
      }
    }
    throw new AssertionError("No new verification email for " + email);
  }

  private static String mailGet(String path) throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(mailpitApiUrl(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return response.body();
  }

  private void assertMailCountRemains(String email, int expected) throws InterruptedException {
    // Negative evidence is observed across the asynchronous mail delivery interval.
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    do {
      assertEquals(expected, inbox.count(email, "dogrula"));
      Thread.sleep(100);
    } while (System.nanoTime() < deadline);
  }

  private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, "Mailpit delivery timed out");
      Thread.sleep(100);
    }
  }

  private static String scalar(String sql, Object... args) throws Exception {
    try (Connection connection = openMigratorConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next(), sql);
        return result.getString(1);
      }
    }
  }

  private static void execute(String sql, Object... args) throws Exception {
    try (Connection connection = openMigratorConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void bind(PreparedStatement statement, Object... args) throws Exception {
    for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
  }
}

/** Docker-free service and method-security checks, colocated to keep this slice in one file. */
class InvitationVerificationServiceContractTest {
  private UserRepository users;
  private WorkspaceInvitationRepository invitations;
  private WorkspaceUserRepository members;
  private WorkspaceRepository workspaces;
  private EmailNotificationPublisher notifications;
  private VerificationTokenRepository tokens;
  private PasswordResetMailBudget budget;
  private AuthService auth;
  private WorkspaceInvitationService invitationService;
  private User user;
  private WorkspaceInvitation invitation;
  private static final String RAW = "contract-invitation";
  private static final String NEED_VERIFICATION =
      "Daveti kabul etmek icin once e-posta adresini dogrula.";

  @BeforeEach
  void setUp() {
    users = mock(UserRepository.class);
    invitations = mock(WorkspaceInvitationRepository.class);
    members = mock(WorkspaceUserRepository.class);
    workspaces = mock(WorkspaceRepository.class);
    notifications = mock(EmailNotificationPublisher.class);
    tokens = mock(VerificationTokenRepository.class);
    budget = mock(PasswordResetMailBudget.class);
    auth =
        new AuthService(
            users,
            mock(RefreshTokenRepository.class),
            tokens,
            mock(PasswordEncoder.class),
            mock(JwtService.class),
            new JwtProperties(),
            mock(BruteForceGuard.class),
            notifications,
            mock(StringRedisTemplate.class),
            new SystemAdminProperties(),
            mock(RefreshTokenFamilyRevoker.class),
            Clock.systemUTC(),
            budget);
    user = User.newUser(UUID.randomUUID(), "invitee@x.test", "hash", "Contract");
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    when(budget.tryAcquireVerification(user.getId())).thenReturn(true);
    invitation =
        WorkspaceInvitation.issue(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "INVITEE@X.TEST",
            WorkspaceRole.VIEWER,
            TokenHasher.sha256Hex(RAW),
            UUID.randomUUID(),
            Instant.now().plusSeconds(3600));
    when(invitations.findByTokenHash(TokenHasher.sha256Hex(RAW)))
        .thenReturn(Optional.of(invitation));
    when(workspaces.findById(invitation.getWorkspaceId()))
        .thenReturn(Optional.of(Workspace.of(invitation.getWorkspaceId(), "Contract workspace")));
    invitationService =
        new WorkspaceInvitationService(invitations, members, workspaces, users, notifications);
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void K1_verifiedCanonicalRecipientCreatesMembershipAndAccepts() {
    user.markEmailVerified();
    var response = invitationService.accept(RAW, user.getId());
    assertEquals(invitation.getWorkspaceId(), response.workspaceId());
    assertEquals("Contract workspace", response.workspaceName());
    assertEquals(WorkspaceRole.VIEWER, response.role());
    ArgumentCaptor<WorkspaceUser> saved = ArgumentCaptor.forClass(WorkspaceUser.class);
    verify(members).save(saved.capture());
    assertEquals(user.getId(), saved.getValue().getUserId());
    assertEquals(invitation.getWorkspaceId(), saved.getValue().getWorkspaceId());
    assertEquals(WorkspaceRole.VIEWER, saved.getValue().getRole());
    assertEquals("ACCEPTED", invitation.getStatus());
  }

  @Test
  void R1_unverifiedHasExactErrorNoMembershipAndPendingInvitationThenVerifiedTwinAccepts() {
    BusinessRuleException error =
        assertThrows(
            BusinessRuleException.class, () -> invitationService.accept(RAW, user.getId()));
    assertEquals(NEED_VERIFICATION, error.getMessage());
    verifyNoInteractions(members);
    assertEquals("PENDING", invitation.getStatus());
    assertNull(invitation.getAcceptedAt());
    user.markEmailVerified();
    assertEquals(
        invitation.getWorkspaceId(), invitationService.accept(RAW, user.getId()).workspaceId());
    verify(members).save(any());
    assertEquals("ACCEPTED", invitation.getStatus());
  }

  @ParameterizedTest(name = "R2 service precedence: {0}")
  @ValueSource(strings = {"invalid", "expired", "revoked", "mismatch"})
  void R2_oldErrorWinsForUnverifiedUserAndRepairedVerifiedTwinAccepts(String scenario) {
    String raw = RAW;
    if (scenario.equals("invalid")) {
      raw = "missing";
    } else if (scenario.equals("expired")) {
      ReflectionTestUtils.setField(invitation, "expiresAt", Instant.now().minusSeconds(1));
    } else if (scenario.equals("revoked")) {
      invitation.revoke();
    } else {
      ReflectionTestUtils.setField(invitation, "email", "other@x.test");
    }
    String requested = raw;
    user.markEmailVerified();
    String expected =
        assertThrows(
                BusinessRuleException.class,
                () -> invitationService.accept(requested, user.getId()))
            .getMessage();
    assertNotNull(expected);
    assertFalse(expected.isBlank());
    assertNotEquals(NEED_VERIFICATION, expected);
    if (scenario.equals("mismatch")) {
      assertTrue(expected.contains("farkli bir e-posta adresine"), expected);
    }
    ReflectionTestUtils.setField(user, "emailVerified", false);
    assertEquals(
        expected,
        assertThrows(
                BusinessRuleException.class,
                () -> invitationService.accept(requested, user.getId()))
            .getMessage());
    verifyNoInteractions(members);
    assertEquals(scenario.equals("revoked") ? "REVOKED" : "PENDING", invitation.getStatus());
    ReflectionTestUtils.setField(invitation, "expiresAt", Instant.now().plusSeconds(3600));
    ReflectionTestUtils.setField(invitation, "status", "PENDING");
    ReflectionTestUtils.setField(invitation, "email", user.getEmail());
    user.markEmailVerified();
    assertEquals(
        invitation.getWorkspaceId(), invitationService.accept(RAW, user.getId()).workspaceId());
    verify(members).save(any());
  }

  @Test
  void K2_unverifiedResendPersistsUsableVerificationTokenAndPublishesMail() {
    auth.resendVerificationEmail(user.getId());
    ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
    verify(notifications)
        .publishVerificationEmail(eq(user.getId()), eq("EMAIL_VERIFICATION"), raw.capture());
    ArgumentCaptor<VerificationToken> saved = ArgumentCaptor.forClass(VerificationToken.class);
    verify(tokens).save(saved.capture());
    assertFalse(raw.getValue().isBlank());
    assertEquals(TokenHasher.sha256Hex(raw.getValue()), saved.getValue().getTokenHash());
    assertEquals(user.getId(), saved.getValue().getUserId());
    assertEquals("EMAIL_VERIFICATION", saved.getValue().getPurpose());
    assertNull(saved.getValue().getUsedAt());
    assertTrue(saved.getValue().getExpiresAt().isAfter(Instant.now()));
    when(tokens.findByTokenHash(TokenHasher.sha256Hex(raw.getValue())))
        .thenReturn(Optional.of(saved.getValue()));
    auth.verifyEmail(raw.getValue());
    assertTrue(user.isEmailVerified());
    assertNotNull(saved.getValue().getUsedAt());
  }

  @Test
  void K3_meResponseReportsFalseThenTrue() {
    assertFalse(MeResponse.from(user).emailVerified());
    user.markEmailVerified();
    assertTrue(MeResponse.from(user).emailVerified());
  }

  @Test
  void K4_verifiedResendDoesNotGenerateTokenSendMailOrSpendBudget() {
    user.markEmailVerified();
    auth.resendVerificationEmail(user.getId());
    verifyNoInteractions(tokens, notifications, budget);
  }

  @Test
  void R3_budgetDenialSendsNothingAndAllowedTwinPublishes() {
    when(budget.tryAcquireVerification(user.getId())).thenReturn(false);
    auth.resendVerificationEmail(user.getId());
    verifyNoInteractions(tokens, notifications);
    when(budget.tryAcquireVerification(user.getId())).thenReturn(true);
    auth.resendVerificationEmail(user.getId());
    verify(tokens).save(any());
    verify(notifications)
        .publishVerificationEmail(eq(user.getId()), eq("EMAIL_VERIFICATION"), anyString());
  }

  @Test
  void R3_verificationAndResetUseDistinctUuidKeysAndContractLimitWindow() {
    RateLimiter limiter = mock(RateLimiter.class);
    when(limiter.hit(anyString(), anyLong(), any()))
        .thenReturn(new RateLimiter.Decision(true, 1, 0));
    PasswordResetMailBudget actual =
        new PasswordResetMailBudget(limiter, new PasswordResetMailProperties());
    assertTrue(actual.tryAcquireVerification(user.getId()));
    assertTrue(actual.tryAcquire(user.getId()));
    UUID other = UUID.randomUUID();
    assertTrue(actual.tryAcquireVerification(other));
    verify(limiter).hit("verify_mail:" + user.getId(), 3, Duration.ofMinutes(15));
    verify(limiter).hit("pwreset_mail:" + user.getId(), 3, Duration.ofMinutes(15));
    verify(limiter).hit("verify_mail:" + other, 3, Duration.ofMinutes(15));
    verifyNoMoreInteractions(limiter);
  }

  @Test
  void R3_realLimiterAllowsThreeDeniesFourthAndAllowsAfterWindowAndOtherUser() {
    BudgetRedis redis = new BudgetRedis();
    PasswordResetMailBudget actual =
        new PasswordResetMailBudget(new RateLimiter(redis), new PasswordResetMailProperties());
    for (int i = 0; i < 3; i++) assertTrue(actual.tryAcquire(user.getId()));
    for (int i = 0; i < 3; i++) assertTrue(actual.tryAcquireVerification(user.getId()));
    assertFalse(actual.tryAcquireVerification(user.getId()));
    assertTrue(actual.tryAcquireVerification(UUID.randomUUID()));
    redis.nowMillis = Duration.ofMinutes(15).toMillis();
    assertTrue(actual.tryAcquireVerification(user.getId()));
    assertEquals(900_000L, redis.lastWindow);
  }

  @Test
  void R3_unavailableRedisFailsOpenAndStillPersistsTokenAndSendsMail() {
    BudgetRedis redis = new BudgetRedis();
    redis.unavailable = true;
    PasswordResetMailBudget actual =
        new PasswordResetMailBudget(new RateLimiter(redis), new PasswordResetMailProperties());
    ReflectionTestUtils.setField(auth, "passwordResetMailBudget", actual);
    auth.resendVerificationEmail(user.getId());
    verify(tokens).save(any());
    verify(notifications)
        .publishVerificationEmail(eq(user.getId()), eq("EMAIL_VERIFICATION"), anyString());
    // Acceptance twin for the same transport when Redis is healthy.
    redis.unavailable = false;
    auth.resendVerificationEmail(user.getId());
    verify(tokens, times(2)).save(any());
    verify(notifications, times(2))
        .publishVerificationEmail(eq(user.getId()), eq("EMAIL_VERIFICATION"), anyString());
  }

  @Test
  void R4_realMethodSecurityDeniesPatAndAllowsJwtCallingOwnUser() {
    MeService meService = mock(MeService.class);
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(MethodSecurityConfig.class);
      context.registerBean(MeController.class, () -> new MeController(meService));
      context.refresh();
      MeController controller = context.getBean(MeController.class);
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  user.getId(), "", List.of(new SimpleGrantedAuthority("AUTH_PAT"))));
      assertThrows(AccessDeniedException.class, controller::resendVerificationEmail);
      verifyNoInteractions(meService);
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  user.getId(), "", List.of(new SimpleGrantedAuthority("AUTH_JWT"))));
      assertDoesNotThrow(controller::resendVerificationEmail);
      verify(meService).resendVerificationEmail(user.getId());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class MethodSecurityConfig {}

  /** Redis protocol double only; the production RateLimiter decides allow/deny/fail-open. */
  static class BudgetRedis extends StringRedisTemplate {
    final Map<String, Long> counts = new HashMap<>();
    final Map<String, Long> deadlines = new HashMap<>();
    long nowMillis;
    long lastWindow;
    boolean unavailable;

    @Override
    @SuppressWarnings("unchecked")
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
      if (unavailable) throw new RedisConnectionFailureException("contract-redis-offline");
      String key = keys.getFirst();
      lastWindow = Long.parseLong(args[0].toString());
      if (nowMillis >= deadlines.getOrDefault(key, 0L)) {
        counts.put(key, 0L);
        deadlines.put(key, nowMillis + lastWindow);
      }
      return (T) List.of(counts.merge(key, 1L, Long::sum), deadlines.get(key) - nowMillis);
    }
  }
}
