package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.notification.EmailNotificationPublisher;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.security.BruteForceGuard;
import com.app.tracker.core.security.JwtProperties;
import com.app.tracker.core.security.JwtService;
import com.app.tracker.core.security.PasswordResetMailBudget;
import com.app.tracker.core.security.RefreshTokenFamilyRevoker;
import com.app.tracker.core.security.SystemAdminProperties;
import com.app.tracker.core.security.TokenHasher;
import com.app.tracker.core.security.dto.LoginRequest;
import com.app.tracker.core.security.repository.RefreshTokenRepository;
import com.app.tracker.core.security.repository.VerificationTokenRepository;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.security.support.MailpitInbox;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.security.support.SecurityMail;
import com.app.tracker.user.model.EmailAddresses;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import com.app.tracker.workspace.model.Workspace;
import com.app.tracker.workspace.model.WorkspaceInvitation;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.repository.WorkspaceInvitationRepository;
import com.app.tracker.workspace.repository.WorkspaceRepository;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import com.app.tracker.workspace.service.WorkspaceInvitationService;
import com.app.tracker.workspace.service.WorkspaceMemberService;
import jakarta.validation.Validator;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/** Independent expectations from dilim-01-sozlesme.md, K1-K9/R1-R4. R5 is manager-owned. */
class CanonicalEmailContractTest {

  @Test
  @Tag("canonical-unit")
  void K1_asciiOnlyUnicodeStripAndUnchangedCharacters() {
    assertAll(
        () ->
            assertEquals("foo.bar@example.com", EmailAddresses.canonical("  Foo.Bar@Example.COM ")),
        () -> assertEquals("İst@x.tr", EmailAddresses.canonical("İst@X.tr")),
        () -> assertEquals("ä@b.de", EmailAddresses.canonical("ä@B.de")),
        () -> assertEquals("ÄİŞ@x.test", EmailAddresses.canonical("\u2003ÄİŞ@X.TEST\u2002")),
        () ->
            assertEquals(
                "abcdefghijklmnopqrstuvwxyz",
                EmailAddresses.canonical("ABCDEFGHIJKLMNOPQRSTUVWXYZ")),
        () ->
            assertEquals(
                "[]^_`{|}~09+-@.\u00a0", EmailAddresses.canonical("[]^_`{|}~09+-@.\u00a0")),
        () -> assertEquals("a b@x.test", EmailAddresses.canonical(" A B@X.TEST ")),
        () -> assertEquals("", EmailAddresses.canonical("\u2003\t\n ")));
  }

  @Test
  @Tag("canonical-unit")
  void K1_nullRejectedAndNonNullAccepted() {
    assertThrows(NullPointerException.class, () -> EmailAddresses.canonical(null));
    assertEquals("", EmailAddresses.canonical(""));
    assertEquals("a@x.test", EmailAddresses.canonical("a@x.test"));
  }

  @Test
  @Tag("canonical-unit")
  void K2_userFactoryStoresCanonicalEmail() {
    User user = User.newUser(UUID.randomUUID(), "\u2003Sec-X@Example.Test\u2002", "hash", "Sec");
    assertEquals("sec-x@example.test", user.getEmail());
  }

  @Test
  @Tag("canonical-unit")
  void K6_invitationFactoryStoresCanonicalEmail() {
    WorkspaceInvitation invitation =
        WorkspaceInvitation.issue(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "\u2003Invitee@X.Test\u2002",
            WorkspaceRole.VIEWER,
            "hash",
            UUID.randomUUID(),
            Instant.now().plusSeconds(600));
    assertEquals("invitee@x.test", invitation.getEmail());
  }

  /** Direct service contract checks complement, but do not replace, HTTP/DB/Mailpit evidence. */
  @Nested
  @Tag("canonical-unit")
  class ServiceBoundaries {
    private UserRepository users;
    private BruteForceGuard guard;
    private EmailNotificationPublisher notifications;
    private JwtService jwt;
    private SystemAdminProperties admins;
    private PasswordResetMailBudget budget;
    private AuthService auth;
    private User account;

    @BeforeEach
    void setUp() {
      users = mock(UserRepository.class);
      guard = mock(BruteForceGuard.class);
      notifications = mock(EmailNotificationPublisher.class);
      jwt = mock(JwtService.class);
      admins = new SystemAdminProperties();
      budget = mock(PasswordResetMailBudget.class);
      PasswordEncoder encoder = mock(PasswordEncoder.class);
      when(encoder.encode(anyString())).thenReturn("hash");
      when(encoder.matches(anyString(), anyString())).thenReturn(true);
      when(guard.checkBeforeAttempt(anyString(), anyString()))
          .thenReturn(new BruteForceGuard.CheckResult(false, false, false));
      when(jwt.issueAccessToken(any(), anyList(), anyString())).thenReturn("access");
      JwtProperties properties = mock(JwtProperties.class);
      when(properties.getRefreshTokenTtl()).thenReturn(Duration.ofDays(1));
      auth =
          new AuthService(
              users,
              mock(RefreshTokenRepository.class),
              mock(VerificationTokenRepository.class),
              encoder,
              jwt,
              properties,
              guard,
              notifications,
              mock(StringRedisTemplate.class),
              admins,
              mock(RefreshTokenFamilyRevoker.class),
              Clock.systemUTC(),
              budget);
      account = User.newUser(UUID.randomUUID(), "foo@x.test", "hash", "Contract");
      when(users.findByEmail("foo@x.test")).thenReturn(Optional.of(account));
    }

    @Test
    void K2_serviceAcceptsDistinctEmailAndPersistsCanonicalIdentity() {
      User created =
          auth.register(
              "  New@X.TEST  ", SecurityFixture.PASSWORD, "New", PrivacyNotice.CURRENT_VERSION);
      assertEquals("new@x.test", created.getEmail());
      verify(users).findByEmail("new@x.test");
      verify(users).save(created);
    }

    @Test
    void R1_serviceRejectsBothDuplicateVariantsWithExistingBusinessError() {
      for (String variant : List.of("foo@x.test", "FOO@X.TEST")) {
        BusinessRuleException error =
            assertThrows(
                BusinessRuleException.class,
                () ->
                    auth.register(
                        variant,
                        SecurityFixture.PASSWORD,
                        "Duplicate",
                        PrivacyNotice.CURRENT_VERSION));
        assertEquals("Bu e-posta zaten kayitli.", error.getMessage());
      }
      verify(users, never()).save(any());
    }

    @Test
    void K3_R3_serviceLoginUsesCanonicalIdentityForLookupAndGuard() {
      assertEquals(
          "access",
          auth.login("\u2003FOO@X.TEST\u2002", SecurityFixture.PASSWORD, "ip").accessToken());
      verify(guard).checkBeforeAttempt("foo@x.test", "ip");
      verify(users).findByEmail("foo@x.test");
      verify(guard).recordSuccess("foo@x.test");
    }

    @Test
    void R2_R3_serviceUnknownVariantRecordsCanonicalFailure() {
      BusinessRuleException error =
          assertThrows(
              BusinessRuleException.class,
              () -> auth.login("UNKNOWN@X.TEST", SecurityFixture.PASSWORD, "ip"));
      assertEquals("E-posta veya sifre hatali.", error.getMessage());
      verify(guard).recordFailure("unknown@x.test", "ip");
    }

    @Test
    void K4_serviceCaseVariantPublishesOneResetForExistingAccount() {
      when(budget.tryAcquire(account.getId())).thenReturn(true);
      auth.requestPasswordReset("  FOO@X.TEST  ");
      verify(notifications)
          .publishVerificationEmail(eq(account.getId()), eq("PASSWORD_RESET"), anyString());
      verify(users).findByEmail("foo@x.test");
    }

    @Test
    void K8_serviceAddsMemberByCanonicalEmail() {
      WorkspaceUserRepository members = mock(WorkspaceUserRepository.class);
      UUID workspaceId = UUID.randomUUID();
      TenantContext.setWorkspaceId(workspaceId);
      try {
        WorkspaceMemberService service = new WorkspaceMemberService(members, users);
        service.addMember("  FOO@X.TEST  ", WorkspaceRole.VIEWER);
        verify(users).findByEmail("foo@x.test");
        verify(members)
            .save(
                org.mockito.ArgumentMatchers.argThat(
                    member ->
                        member.getWorkspaceId().equals(workspaceId)
                            && member.getUserId().equals(account.getId())
                            && member.getRole().equals(WorkspaceRole.VIEWER)));
      } finally {
        TenantContext.clear();
      }
    }

    @Test
    void K9_serviceCanonicalizesConfiguredAndAccountEmailsBeforePromotion() {
      admins.setSystemAdminEmails(List.of("\u2003Foo@X.Test\u2002"));
      // Model hydrated at a repository boundary; both comparison inputs require strip/ASCII fold.
      ReflectionTestUtils.setField(account, "email", "\u2003FOO@X.TEST\u2002");
      auth.login("foo@x.test", SecurityFixture.PASSWORD, "ip");
      assertTrue(account.isSystemAdmin());
      verify(jwt)
          .issueAccessToken(eq(account.getId()), eq(List.of("USER", "SYSTEM_ADMIN")), anyString());
    }

    @Test
    void K9_serviceDifferentConfiguredEmailDoesNotPromoteAcceptedLogin() {
      admins.setSystemAdminEmails(List.of("Other@X.test"));
      assertEquals(
          "access", auth.login("foo@x.test", SecurityFixture.PASSWORD, "ip").accessToken());
      assertFalse(account.isSystemAdmin());
    }

    @Test
    void K7_serviceCanonicalComparisonAcceptsVariantsAndRejectsDifferentIdentity() {
      WorkspaceInvitationRepository invitations = mock(WorkspaceInvitationRepository.class);
      WorkspaceUserRepository members = mock(WorkspaceUserRepository.class);
      WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
      UUID workspaceId = UUID.randomUUID();
      String token = "contract-token";
      WorkspaceInvitation invitation =
          WorkspaceInvitation.issue(
              UUID.randomUUID(),
              workspaceId,
              "foo@x.test",
              WorkspaceRole.VIEWER,
              TokenHasher.sha256Hex(token),
              UUID.randomUUID(),
              Instant.now().plusSeconds(600));
      ReflectionTestUtils.setField(invitation, "email", "\u2003FOO@X.TEST\u2002");
      ReflectionTestUtils.setField(account, "email", "\u2003Foo@X.Test\u2002");
      account.markEmailVerified();
      when(invitations.findByTokenHash(TokenHasher.sha256Hex(token)))
          .thenReturn(Optional.of(invitation));
      when(users.findById(account.getId())).thenReturn(Optional.of(account));
      when(workspaces.findById(workspaceId))
          .thenReturn(Optional.of(Workspace.of(workspaceId, "Contract")));
      WorkspaceInvitationService service =
          new WorkspaceInvitationService(invitations, members, workspaces, users, notifications);

      User other = User.newUser(UUID.randomUUID(), "other@x.test", "hash", "Other");
      other.markEmailVerified();
      when(users.findById(other.getId())).thenReturn(Optional.of(other));
      assertThrows(BusinessRuleException.class, () -> service.accept(token, other.getId()));
      assertEquals(WorkspaceInvitation.PENDING, invitation.getStatus());
      verify(members, never()).save(any());

      assertEquals(workspaceId, service.accept(token, account.getId()).workspaceId());
      assertEquals(WorkspaceInvitation.ACCEPTED, invitation.getStatus());
      verify(members)
          .save(
              org.mockito.ArgumentMatchers.argThat(
                  member ->
                      member.getUserId().equals(account.getId())
                          && member.getWorkspaceId().equals(workspaceId)));
    }
  }

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  class HttpAndDatabase extends AbstractIntegrationTest {

    private static final String PASSWORD = SecurityFixture.PASSWORD;
    private static final String ADMIN_EMAIL = "admin-" + UUID.randomUUID() + "@x.test";
    private static final int LOCK_THRESHOLD = 10;

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;
    @Autowired private AuthService authService;
    @Autowired private JwtService jwtService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper mapper;
    @Autowired private Validator validator;

    private SecurityHttp http;
    private MailpitInbox inbox;
    private SecurityMail mail;

    @DynamicPropertySource
    static void systemAdminEmail(DynamicPropertyRegistry registry) {
      registry.add("app.security.system-admin-emails", () -> "  " + upper(ADMIN_EMAIL) + "  ");
    }

    @BeforeEach
    void setUp() {
      http = new SecurityHttp(port);
      inbox = new MailpitInbox(HttpAndDatabase::mailpitApiUrl, mapper);
      mail = new SecurityMail(HttpAndDatabase::mailpitApiUrl, mapper);
    }

    @Test
    void K2_registrationPersistsAndReturnsCanonicalEmail() throws Exception {
      String lower = email("sec-x");
      HttpResponse<String> response = register(upper(lower));
      assertEquals(201, response.statusCode(), response.body());
      assertEquals(lower, scalar("SELECT email FROM users WHERE lower(email) = ?", lower));
    }

    @Test
    void K3_loginAcceptsBothCaseVariants() {
      String lower = email("foo");
      assertEquals(201, register(upper(lower)).statusCode());
      for (String variant : List.of(lower, upper(lower))) {
        HttpResponse<String> response = login(variant, PASSWORD);
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(mapper.readTree(response.body()).path("accessToken").asString().isBlank());
      }
    }

    @Test
    void K3_serviceStripsPaddingAndHttpHonorsDtoException() {
      String lower = email("padded");
      assertEquals(201, register(lower).statusCode());
      assertNotNull(
          authService
              .login("\u2003" + upper(lower) + "\u2002", PASSWORD, uniqueIp())
              .accessToken());
      String padded = " " + upper(lower) + " ";
      HttpResponse<String> response = login(padded, PASSWORD);
      // K3 permits any 4xx only when the actual DTO validator rejects the padded email.
      if (validator.validate(new LoginRequest(padded, PASSWORD)).isEmpty()) {
        assertEquals(200, response.statusCode(), response.body());
      } else {
        assertTrue(
            response.statusCode() == 200
                || (response.statusCode() >= 400 && response.statusCode() < 500),
            response.body());
      }
      assertEquals(200, login(lower, PASSWORD).statusCode(), "unpadded acceptance pair");
    }

    @Test
    void K4_caseVariantResetSendsExactlyOneMailToAccount() throws Exception {
      String lower = email("reset");
      assertEquals(201, register(lower).statusCode());
      HttpResponse<String> response =
          anonymousPost("/api/v1/auth/password-reset/request", Map.of("email", upper(lower)));
      assertEquals(202, response.statusCode(), response.body());
      assertFalse(mail.rawToken(lower, "sifirlama").isBlank());
      assertEquals(1, inbox.count(lower, "sifirlama"));
      assertEquals(
          "1",
          scalar(
              "SELECT count(*) FROM verification_tokens t JOIN users u ON u.id=t.user_id WHERE u.email=? AND t.purpose='PASSWORD_RESET'",
              lower));
    }

    @Test
    void K5_R1_distinctEmailsBothRegisterAcceptancePair() throws Exception {
      String a = email("a");
      String b = email("b");
      assertEquals(201, register(a).statusCode());
      assertEquals(201, register(b).statusCode());
      assertEquals("2", scalar("SELECT count(*) FROM users WHERE email IN (?,?)", a, b));
    }

    @Test
    void K6_reinviteCaseVariantRevokesOnlyPriorPendingInvitation() throws Exception {
      Tenant tenant = fixture().a();
      String lower = email("invitee");
      HttpResponse<String> first = invite(tenant, upper(lower));
      assertEquals(201, first.statusCode(), first.body());
      String firstId = mapper.readTree(first.body()).path("id").asString();
      assertEquals(
          lower,
          scalar("SELECT email FROM workspace_invitations WHERE id=?", UUID.fromString(firstId)));
      HttpResponse<String> second = invite(tenant, "INVITEE" + lower.substring("invitee".length()));
      assertEquals(201, second.statusCode(), second.body());
      assertEquals(
          "REVOKED",
          scalar("SELECT status FROM workspace_invitations WHERE id=?", UUID.fromString(firstId)));
      assertEquals(
          "1",
          scalar(
              "SELECT count(*) FROM workspace_invitations WHERE workspace_id=? AND lower(email)=? AND status='PENDING'",
              tenant.workspaceId(),
              lower));
      assertEquals(
          "2",
          scalar(
              "SELECT count(*) FROM workspace_invitations WHERE workspace_id=? AND email=?",
              tenant.workspaceId(),
              lower));
    }

    @Test
    void K7_verifiedCaseVariantAcceptsInvitationAndCreatesMembership() throws Exception {
      Tenant tenant = fixture().a();
      String lower = email("invitee");
      assertEquals(201, invite(tenant, upper(lower)).statusCode());
      String token = inbox.inviteToken(lower);
      Actor actor = verifiedActor(lower);
      assertAcceptedMembership(tenant, token, actor);
    }

    @Test
    void K7_canonicalComparisonHandlesDbPermittedPaddingOnEitherSide() throws Exception {
      Tenant tenant = fixture().a();
      // R4 explicitly has no whitespace CHECK. Exercise the comparison itself with legal DB values;
      // ordinary HTTP-created identities are already canonical before reaching this boundary.
      for (boolean padInvitation : List.of(true, false)) {
        String lower = email("comparison");
        assertEquals(201, invite(tenant, upper(lower)).statusCode());
        String token = inbox.inviteToken(lower);
        Actor actor = verifiedActor(lower);
        if (padInvitation) {
          execute(
              "UPDATE workspace_invitations SET email=? WHERE workspace_id=? AND email=?",
              "\u2003" + lower + "\u2002",
              tenant.workspaceId(),
              lower);
        } else {
          execute(
              "UPDATE users SET email=? WHERE id=?", "\u2003" + lower + "\u2002", actor.userId());
        }
        try {
          assertAcceptedMembership(tenant, token, actor);
        } finally {
          if (padInvitation) {
            execute(
                "UPDATE workspace_invitations SET email=? WHERE workspace_id=? AND email=?",
                lower,
                tenant.workspaceId(),
                "\u2003" + lower + "\u2002");
          } else {
            execute("UPDATE users SET email=? WHERE id=?", lower, actor.userId());
          }
        }
      }
    }

    @Test
    void K7_differentVerifiedEmailRejectedWithMatchingAcceptancePair() throws Exception {
      Tenant tenant = fixture().a();
      String target = email("target");
      assertEquals(201, invite(tenant, upper(target)).statusCode());
      String token = inbox.inviteToken(target);
      Actor other = verifiedActor(email("other"));
      HttpResponse<String> rejected =
          http.post("/api/v1/invitations/" + token + "/accept", other, null, null);
      assertEquals(400, rejected.statusCode(), rejected.body());
      assertEquals(
          "0",
          scalar(
              "SELECT count(*) FROM workspace_users WHERE workspace_id=? AND user_id=?",
              tenant.workspaceId(),
              other.userId()));
      assertEquals(
          "PENDING",
          scalar(
              "SELECT status FROM workspace_invitations WHERE workspace_id=? AND email=?",
              tenant.workspaceId(),
              target));
      assertAcceptedMembership(tenant, token, verifiedActor(target));
    }

    @Test
    void K8_adminAddsRegisteredMemberByUppercaseEmail() throws Exception {
      Tenant tenant = fixture().a();
      String lower = email("member");
      UUID id =
          authService.register(lower, PASSWORD, "Member", PrivacyNotice.CURRENT_VERSION).getId();
      HttpResponse<String> response =
          http.post(
              "/api/v1/workspaces/members",
              tenant.admin(),
              tenant.workspaceId(),
              json(Map.of("email", upper(lower), "role", WorkspaceRole.VIEWER)));
      assertEquals(201, response.statusCode(), response.body());
      assertEquals(
          "1",
          scalar(
              "SELECT count(*) FROM workspace_users WHERE workspace_id=? AND user_id=?",
              tenant.workspaceId(),
              id));
    }

    @Test
    void K9_configuredMixedCaseAndPaddedEmailPromotedOnFirstLogin() throws Exception {
      assertEquals(201, register(ADMIN_EMAIL).statusCode());
      assertEquals(
          "false", scalar("SELECT system_admin::text FROM users WHERE email=?", ADMIN_EMAIL));
      HttpResponse<String> response = login(ADMIN_EMAIL, PASSWORD);
      assertEquals(200, response.statusCode(), response.body());
      String token = mapper.readTree(response.body()).path("accessToken").asString();
      assertTrue(jwtService.verify(token).roles().contains("SYSTEM_ADMIN"));
      assertEquals(
          "true", scalar("SELECT system_admin::text FROM users WHERE email=?", ADMIN_EMAIL));
    }

    @Test
    void K9_unconfiguredEmailStaysRegularWhileLoginAccepted() throws Exception {
      String lower = email("regular");
      assertEquals(201, register(lower).statusCode());
      HttpResponse<String> response = login(lower, PASSWORD);
      assertEquals(200, response.statusCode(), response.body());
      assertFalse(
          jwtService
              .verify(mapper.readTree(response.body()).path("accessToken").asString())
              .roles()
              .contains("SYSTEM_ADMIN"));
      assertEquals("false", scalar("SELECT system_admin::text FROM users WHERE email=?", lower));
    }

    @Test
    void R1_bothDuplicateCaseVariantsReturnExistingBusinessErrorAndOneRow() throws Exception {
      String lower = email("foo");
      assertEquals(201, register(upper(lower)).statusCode());
      for (String variant : List.of(lower, upper(lower))) {
        HttpResponse<String> response = register(variant);
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("Bu e-posta zaten kayitli."), response.body());
        assertEquals("1", scalar("SELECT count(*) FROM users WHERE lower(email)=?", lower));
      }
      assertEquals(201, register(email("distinct")).statusCode(), "distinct acceptance pair");
    }

    @Test
    void R2_unknownEmailRetainsErrorAndKnownAccountLogsIn() {
      HttpResponse<String> response = login(email("absent"), PASSWORD);
      assertEquals(400, response.statusCode(), response.body());
      assertTrue(response.body().contains("E-posta veya sifre hatali."), response.body());
      String known = email("known");
      assertEquals(201, register(known).statusCode());
      assertEquals(200, login(known, PASSWORD).statusCode());
    }

    @Test
    void R3_caseVariantsShareRedisCounterAndCannotBypassLock() {
      String lower = email("lock");
      String mixed = upper(lower);
      assertEquals(201, register(lower).statusCode());
      for (int i = 1; i <= LOCK_THRESHOLD; i++) {
        // Only remove backoff timestamps, never failure counts or locks (same fixture as sample).
        redis.delete(
            List.of("login_fail:user:" + lower + ":last", "login_fail:user:" + mixed + ":last"));
        HttpResponse<String> response =
            login(i == LOCK_THRESHOLD ? lower : mixed, "incorrect-password");
        assertEquals(400, response.statusCode(), response.body());
        assertEquals(Integer.toString(i), redis.opsForValue().get("login_fail:user:" + lower));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("login_fail:user:" + mixed)));
      }
      for (String variant : List.of(lower, mixed)) {
        HttpResponse<String> locked = login(variant, PASSWORD);
        assertEquals(400, locked.statusCode(), locked.body());
        assertTrue(locked.body().contains("Hesap gecici olarak kilitlendi."), locked.body());
        assertFalse(locked.body().contains("accessToken"));
      }
    }

    @Test
    void R3_belowThresholdCaseVariantLoginIsAccepted() {
      String lower = email("below");
      assertEquals(201, register(lower).statusCode());
      assertEquals(400, login(upper(lower), "incorrect-password").statusCode());
      assertEquals(200, login(lower, PASSWORD).statusCode(), "lock rejection acceptance pair");
      assertFalse(Boolean.TRUE.equals(redis.hasKey("login_fail:user:" + lower)));
    }

    @Test
    void R4_usersCheckRejectsAsciiUppercaseAndAcceptsCanonicalAndNonAscii() throws Exception {
      String sql =
          "INSERT INTO users(id,email,password_hash,full_name) VALUES (?,?,'hash','Contract')";
      assertCheckViolation(
          "users_email_canonical", sql, UUID.randomUUID(), "X-" + UUID.randomUUID() + "@y.test");
      assertInsertAccepted(sql, UUID.randomUUID(), email("canonical"));
      assertInsertAccepted(sql, UUID.randomUUID(), "ÄİŞ-" + UUID.randomUUID() + "@x.test");
    }

    @Test
    void R4_invitationsCheckRejectsAsciiUppercaseAndAcceptsCanonicalAndNonAscii() throws Exception {
      Tenant tenant = fixture().a();
      String sql =
          "INSERT INTO workspace_invitations(id,workspace_id,email,role,token_hash,invited_by,expires_at) VALUES (?,?,?,'VIEWER',?,?,now()+interval '1 day')";
      assertCheckViolation(
          "workspace_invitations_email_canonical",
          sql,
          UUID.randomUUID(),
          tenant.workspaceId(),
          "X-" + UUID.randomUUID() + "@y.test",
          UUID.randomUUID().toString(),
          tenant.admin().userId());
      for (String address : List.of(email("canonical"), "ÄİŞ-" + UUID.randomUUID() + "@x.test")) {
        assertInsertAccepted(
            sql,
            UUID.randomUUID(),
            tenant.workspaceId(),
            address,
            UUID.randomUUID().toString(),
            tenant.admin().userId());
      }
    }

    private SecurityFixture fixture() {
      return SecurityFixture.create(context, port);
    }

    private Actor verifiedActor(String email) throws SQLException {
      UUID id =
          authService.register(email, PASSWORD, "Invitee", PrivacyNotice.CURRENT_VERSION).getId();
      execute("UPDATE users SET email_verified=true WHERE id=?", id);
      String token = authService.login(email, PASSWORD, uniqueIp()).accessToken();
      return new Actor(id, email, token);
    }

    private void assertAcceptedMembership(Tenant tenant, String token, Actor actor)
        throws SQLException {
      HttpResponse<String> response =
          http.post("/api/v1/invitations/" + token + "/accept", actor, null, null);
      assertEquals(200, response.statusCode(), response.body());
      assertEquals(
          tenant.workspaceId().toString(),
          mapper.readTree(response.body()).path("workspaceId").asString());
      assertEquals(
          "VIEWER",
          scalar(
              "SELECT role FROM workspace_users WHERE workspace_id=? AND user_id=?",
              tenant.workspaceId(),
              actor.userId()));
    }

    private HttpResponse<String> invite(Tenant tenant, String email) {
      return http.post(
          "/api/v1/workspaces/members/invitations",
          tenant.admin(),
          tenant.workspaceId(),
          json(Map.of("email", email, "role", WorkspaceRole.VIEWER)));
    }

    private HttpResponse<String> register(String email) {
      return anonymousPost(
          "/api/v1/auth/register",
          Map.of(
              "email",
              email,
              "password",
              PASSWORD,
              "fullName",
              "Sec Contract",
              "privacyNoticeAccepted",
              true,
              "privacyNoticeVersion",
              PrivacyNotice.CURRENT_VERSION));
    }

    private HttpResponse<String> login(String email, String password) {
      return anonymousPost("/api/v1/auth/login", Map.of("email", email, "password", password));
    }

    private HttpResponse<String> anonymousPost(String path, Map<String, ?> body) {
      return http.send("POST", path, null, null, json(body), Map.of("X-Forwarded-For", uniqueIp()));
    }

    private String json(Map<String, ?> body) {
      return mapper.writeValueAsString(body);
    }

    private static String email(String tag) {
      return tag + "-" + UUID.randomUUID() + "@x.test";
    }

    private static String upper(String email) {
      return email.toUpperCase(Locale.ROOT);
    }

    private static String uniqueIp() {
      ThreadLocalRandom r = ThreadLocalRandom.current();
      return "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
    }

    private static String scalar(String sql, Object... args) throws SQLException {
      try (Connection connection = openMigratorConnection();
          PreparedStatement statement = connection.prepareStatement(sql)) {
        bind(statement, args);
        try (ResultSet result = statement.executeQuery()) {
          assertTrue(result.next(), sql);
          return result.getString(1);
        }
      }
    }

    private static void execute(String sql, Object... args) throws SQLException {
      try (Connection connection = openMigratorConnection();
          PreparedStatement statement = connection.prepareStatement(sql)) {
        bind(statement, args);
        statement.executeUpdate();
      }
    }

    private static void assertCheckViolation(String constraint, String sql, Object... args)
        throws SQLException {
      try (Connection connection = openMigratorConnection();
          PreparedStatement statement = connection.prepareStatement(sql)) {
        connection.setAutoCommit(false);
        bind(statement, args);
        try {
          SQLException error = assertThrows(SQLException.class, statement::executeUpdate);
          assertEquals("23514", error.getSQLState());
          assertTrue(error.getMessage().contains(constraint), error.getMessage());
        } finally {
          connection.rollback();
        }
      }
    }

    private static void assertInsertAccepted(String sql, Object... args) throws SQLException {
      try (Connection connection = openMigratorConnection();
          PreparedStatement statement = connection.prepareStatement(sql)) {
        connection.setAutoCommit(false);
        bind(statement, args);
        try {
          assertEquals(1, statement.executeUpdate(), "canonical/non-ASCII acceptance pair");
        } finally {
          connection.rollback();
        }
      }
    }
  }
}
