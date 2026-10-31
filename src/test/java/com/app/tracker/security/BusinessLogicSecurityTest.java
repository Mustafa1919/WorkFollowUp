package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.security.support.MailpitInbox;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.workspace.model.WorkspaceRole;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guvenlik katalogu BL-03 / BL-04: is kurali ihlalleri API'de uygulanir. BL-03 davet kabul
 * eslesmesi (dogrulanmamis hesap, harf buyuklugu varyanti), BL-04 son ADMIN korumasi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusinessLogicSecurityTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private ApplicationContext ctx;
  @Autowired private ObjectMapper mapper;
  @Autowired private AuthService authService;

  private SecurityFixture fixture;
  private SecurityHttp http;
  private MailpitInbox inbox;

  @BeforeAll
  void setUp() {
    http = new SecurityHttp(port);
    fixture = SecurityFixture.create(ctx, port);
    inbox = new MailpitInbox(path -> mailpitApiUrl(path), mapper);
  }

  private record Account(UUID id, String email, String accessToken) {}

  // =========================================================================================
  // BL-03 davet kabul eslesmesi
  // =========================================================================================

  @Test
  @DisplayName("BL-03 | KARAR: dogrulanmamis birebir adresin davet kabulu")
  void bl03_unverifiedAccountCannotAcceptInvitation() {
    Tenant a = fixture.a();
    String email = "sec-bl03u-" + UUID.randomUUID() + "@tracker.local";
    Account account = newAccount(email);
    String token = invite(a, email);

    HttpResponse<String> accept = accept(token, account);

    assertAll(
        () ->
            assertTrue(
                accept.statusCode() >= 400 && accept.statusCode() < 500,
                "KARAR GEREKLI: dogrulanmamis hesap daveti kabul etti (durum="
                    + accept.statusCode()
                    + "); beklenen 4xx. govde="
                    + accept.body()),
        () ->
            assertFalse(
                memberIds(a).contains(account.id().toString()),
                "dogrulanmamis hesap workspace uyesi oldu"));
  }

  @Test
  @DisplayName("BL-03 | local-part harf varyanti (Sec-x@ daveti, sec-x@ hesabi) kabul ETTIREMEZ")
  void bl03_localPartCaseVariantUnverifiedAccountCannotAcceptInvitation() {
    Tenant a = fixture.a();
    String rest = UUID.randomUUID() + "@x.test";
    String invited = "Sec-bl03l-" + rest;
    String variant = "sec-bl03l-" + rest;
    Account account = newAccount(variant);
    String token = invite(a, invited);

    HttpResponse<String> accept = accept(token, account);

    assertAll(
        () ->
            assertTrue(
                accept.statusCode() >= 400 && accept.statusCode() < 500,
                "local-part varyanti hesap ("
                    + variant
                    + ") "
                    + invited
                    + " davetini kabul etti: durum="
                    + accept.statusCode()
                    + " govde="
                    + accept.body()),
        () ->
            assertFalse(
                memberIds(a).contains(account.id().toString()),
                "local-part varyanti hesap workspace uyesi oldu"));
  }

  @Test
  @DisplayName("BL-03 | harf buyuklugu varyanti dogrulanmamis hesap daveti kabul ETTIREMEZ")
  void bl03_caseVariantUnverifiedAccountCannotAcceptInvitation() {
    Tenant a = fixture.a();
    String local = "sec-bl03c-" + UUID.randomUUID();
    String invited = local + "@Tracker.Local";
    String variant = local + "@tracker.local";
    Account account = newAccount(variant);
    String token = invite(a, invited);

    HttpResponse<String> accept = accept(token, account);

    assertAll(
        () ->
            assertTrue(
                accept.statusCode() >= 400 && accept.statusCode() < 500,
                "varyant hesap ("
                    + variant
                    + ") "
                    + invited
                    + " davetini kabul etti: durum="
                    + accept.statusCode()
                    + " govde="
                    + accept.body()),
        () ->
            assertFalse(
                memberIds(a).contains(account.id().toString()),
                "varyant hesap workspace uyesi oldu"));
  }

  @Test
  @DisplayName("BL-03 | K: dogrulanmis, birebir e-posta sahibi daveti kabul eder")
  void bl03_acceptance_verifiedExactOwnerAccepts() throws Exception {
    Tenant a = fixture.a();
    String email = "sec-bl03k-" + UUID.randomUUID() + "@tracker.local";
    Account account = newAccount(email);
    markVerified(account.id());
    String token = invite(a, email);

    HttpResponse<String> accept = accept(token, account);

    assertEquals(200, accept.statusCode(), accept.body());
    JsonNode body = mapper.readTree(accept.body());
    assertAll(
        () -> assertEquals(a.workspaceId().toString(), body.path("workspaceId").asString("")),
        () -> assertEquals(WorkspaceRole.DEVELOPER, body.path("role").asString("")),
        () -> assertTrue(memberIds(a).contains(account.id().toString()), "uye listesinde yok"));
  }

  // =========================================================================================
  // BL-04 son ADMIN korumasi
  // =========================================================================================

  @Test
  @DisplayName("BL-04 | workspace'in son ADMIN'i rolunu dusuremez (400/422)")
  void bl04_lastAdminCannotDemoteSelf() {
    Tenant a = fixture.a();
    Actor admin = a.admin();

    HttpResponse<String> r =
        http.patch(
            "/api/v1/workspaces/members/" + admin.userId(),
            admin,
            a.workspaceId(),
            "{\"role\":\"" + WorkspaceRole.MANAGER + "\"}");

    assertAll(
        () ->
            assertTrue(
                Set.of(400, 422).contains(r.statusCode()),
                "beklenen 400/422, gozlenen " + r.statusCode() + " " + r.body()),
        () -> assertEquals(WorkspaceRole.ADMIN, roleOf(a, admin.userId()), "rol degismemeli"));
  }

  @Test
  @DisplayName("BL-04 | workspace'in son ADMIN'i kendini cikaramaz (400/422)")
  void bl04_lastAdminCannotRemoveSelf() {
    Tenant a = fixture.a();
    Actor admin = a.admin();

    HttpResponse<String> r =
        http.delete("/api/v1/workspaces/members/" + admin.userId(), admin, a.workspaceId(), null);

    assertAll(
        () ->
            assertTrue(
                Set.of(400, 422).contains(r.statusCode()),
                "beklenen 400/422, gozlenen " + r.statusCode() + " " + r.body()),
        () -> assertEquals(WorkspaceRole.ADMIN, roleOf(a, admin.userId()), "uyelik silinmemeli"));
  }

  @Test
  @DisplayName("BL-04 | K: ikinci ADMIN varken ilk ADMIN rolunu dusurebilir")
  void bl04_acceptance_adminCanDemoteSelfWhenAnotherAdminExists() {
    Tenant b = fixture.b();
    Actor first = fixture.addMember(b, WorkspaceRole.ADMIN, "bl04-demote-first");
    Actor second = fixture.addMember(b, WorkspaceRole.ADMIN, "bl04-demote-second");

    HttpResponse<String> r =
        http.patch(
            "/api/v1/workspaces/members/" + first.userId(),
            first,
            b.workspaceId(),
            "{\"role\":\"" + WorkspaceRole.MANAGER + "\"}");

    assertEquals(200, r.statusCode(), r.body());
    assertAll(
        () -> assertEquals(WorkspaceRole.MANAGER, roleOf(b, first.userId())),
        () -> assertEquals(WorkspaceRole.ADMIN, roleOf(b, second.userId())));
  }

  @Test
  @DisplayName("BL-04 | K: ikinci ADMIN varken ADMIN kendini cikarabilir")
  void bl04_acceptance_adminCanRemoveSelfWhenAnotherAdminExists() {
    Tenant b = fixture.b();
    Actor first = fixture.addMember(b, WorkspaceRole.ADMIN, "bl04-remove-first");
    Actor second = fixture.addMember(b, WorkspaceRole.ADMIN, "bl04-remove-second");

    HttpResponse<String> r =
        http.delete("/api/v1/workspaces/members/" + first.userId(), first, b.workspaceId(), null);

    assertEquals(204, r.statusCode(), r.body());
    assertAll(
        () -> assertFalse(memberIds(b).contains(first.userId().toString()), "uye silinmedi"),
        () -> assertEquals(WorkspaceRole.ADMIN, roleOf(b, second.userId())));
  }

  // =========================================================================================
  // Yardimcilar
  // =========================================================================================

  private Account newAccount(String email) {
    UUID id =
        authService
            .register(email, SecurityFixture.PASSWORD, "Sec bl", PrivacyNotice.CURRENT_VERSION)
            .getId();
    String token = authService.login(email, SecurityFixture.PASSWORD, "127.0.0.1").accessToken();
    return new Account(id, email, token);
  }

  /** Tenant ADMIN'i davet gonderir; ham token Mailpit'teki davet baglantisindan okunur. */
  private String invite(Tenant tenant, String email) {
    HttpResponse<String> r =
        http.post(
            "/api/v1/workspaces/members/invitations",
            tenant.admin(),
            tenant.workspaceId(),
            "{\"email\":\"" + email + "\",\"role\":\"" + WorkspaceRole.DEVELOPER + "\"}");
    assertEquals(201, r.statusCode(), r.body());
    return inbox.inviteToken(email);
  }

  private HttpResponse<String> accept(String token, Account account) {
    return http.post("/api/v1/invitations/" + token + "/accept", account.accessToken(), null, null);
  }

  private void markVerified(UUID userId) throws SQLException {
    // app_migrator (tablo sahibi) ile duz JDBC; e-posta dogrulama akisinin sonucunu taklit eder.
    try (Connection c = openMigratorConnection();
        PreparedStatement ps =
            c.prepareStatement("UPDATE users SET email_verified = true WHERE id = ?")) {
      ps.setObject(1, userId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private Set<String> memberIds(Tenant tenant) {
    JsonNode list = members(tenant);
    Set<String> ids = new java.util.HashSet<>();
    for (JsonNode m : list) {
      ids.add(m.path("userId").asString(""));
    }
    return ids;
  }

  private String roleOf(Tenant tenant, UUID userId) {
    for (JsonNode m : members(tenant)) {
      if (userId.toString().equals(m.path("userId").asString(""))) {
        return m.path("role").asString("");
      }
    }
    return null;
  }

  private JsonNode members(Tenant tenant) {
    // Okuma icin fikstur MANAGER'i: hicbir BL-03/BL-04 testi onun uyeligine dokunmaz.
    HttpResponse<String> r =
        http.get("/api/v1/workspaces/members", tenant.manager(), tenant.workspaceId(), null);
    assertEquals(200, r.statusCode(), r.body());
    return mapper.readTree(r.body());
  }
}
