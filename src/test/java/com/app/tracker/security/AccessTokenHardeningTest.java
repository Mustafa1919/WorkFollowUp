package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.accesstoken.service.PersonalAccessTokenService;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.security.support.JwtForge;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.user.model.PrivacyNotice;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guvenlik katalogu §2: kisisel erisim token'lari (PAT) — TOK-11, TOK-13, TOK-14, TOK-15 (TOK-12
 * mevcut {@code AccessTokenIntegrationTest}'te). Gercek servlet uzerinden; PAT dogrulamasi {@code
 * GET /api/v1/projects} (ADMIN, kendi workspace'i) ile olculur.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessTokenHardeningTest extends AbstractIntegrationTest {

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
  private static final String PROJECTS = "/api/v1/projects";

  @LocalServerPort private int port;
  @Autowired private ApplicationContext ctx;
  @Autowired private PersonalAccessTokenService patService;
  @Autowired private AuthService authService;
  @Autowired private ObjectMapper mapper;

  private SecurityFixture fx;
  private Tenant tenant;
  private Actor admin;

  @BeforeEach
  void setUp() {
    fx = SecurityFixture.create(ctx, port);
    tenant = fx.a();
    admin = tenant.admin();
  }

  // =========================================================================================
  // TOK-11 PAT depolama / gosterim
  // =========================================================================================

  @Test
  @DisplayName(
      "TOK-11 | rawToken olusturma yanitinda bir kez doner; listede yok; DB'de yalniz 64 hex hash")
  void tok11_rawTokenShownOnceAndStoredHashed() throws Exception {
    HttpResponse<String> created =
        fx.http().post("/api/v1/access-tokens", admin, null, "{\"name\":\"tok11\"}");
    assertEquals(201, created.statusCode(), created.body());
    JsonNode createdJson = mapper.readTree(created.body());
    String raw = createdJson.path("rawToken").asString("");
    String tokenId = createdJson.path("token").path("id").asString("");
    assertTrue(raw.startsWith("wf_pat_"), "olusturma yanitinda rawToken yok: " + created.body());

    HttpResponse<String> list = fx.http().get("/api/v1/access-tokens", admin, null, null);
    assertEquals(200, list.statusCode(), list.body());

    String rowAsText =
        queryString(
            "SELECT t::text FROM personal_access_tokens t WHERE t.id = ?",
            UUID.fromString(tokenId));
    String hash =
        queryString(
            "SELECT token_hash FROM personal_access_tokens WHERE id = ?", UUID.fromString(tokenId));
    assertAll(
        () -> assertTrue(list.body().contains(tokenId), "kabul esigi: listede token gorunmeli"),
        () -> assertFalse(list.body().contains(raw), "listeleme yanitinda duz token"),
        () -> assertFalse(list.body().contains("rawToken"), "listeleme yanitinda rawToken alani"),
        () -> assertFalse(rowAsText.contains(raw), "duz token personal_access_tokens satirinda"),
        () -> assertTrue(SHA256_HEX.matcher(hash).matches(), "token_hash 64 hex degil: " + hash),
        () -> assertEquals(sha256(raw), hash, "token_hash sha256(duz) degil"));
  }

  // =========================================================================================
  // TOK-13 PAT SYSTEM_ADMIN tasimaz
  // =========================================================================================

  @Test
  @DisplayName(
      "TOK-13 | SYSTEM_ADMIN kullanicinin PAT'i ile sistem yoneticisi ucu 403; JWT'si ile gecer")
  void tok13_patDoesNotCarrySystemAdmin() throws Exception {
    // Sistem yoneticisi: users.is_system_admin DB'de isaretlenir, ardindan login (yeni context
    // yok).
    String email = "sec-tok13-" + UUID.randomUUID() + "@tracker.local";
    UUID userId =
        authService
            .register(email, SecurityFixture.PASSWORD, "Sec tok13", PrivacyNotice.CURRENT_VERSION)
            .getId();
    assertEquals(1, execute("UPDATE users SET system_admin = true WHERE id = ?", userId));
    String jwt = authService.login(email, SecurityFixture.PASSWORD, "127.0.0.1").accessToken();
    assertTrue(
        JwtForge.decodePayload(jwt).contains("SYSTEM_ADMIN"),
        "on kosul: JWT SYSTEM_ADMIN tasimali");
    String pat = patService.create(userId, "tok13", null).rawToken();

    // Izole, bos, bu testte olusturulan konu: replay gercekten calisir ve 0 mesaj yeniden yayinlar.
    String dltTopic = "sec-tok13-" + UUID.randomUUID() + "-dlt";
    createTopic(dltTopic);
    String body =
        "{\"dltTopic\":\"" + dltTopic + "\",\"partition\":0,\"fromOffset\":0,\"toOffset\":0}";

    HttpResponse<String> withJwt = fx.http().post("/api/v1/admin/kafka/replay", jwt, null, body);
    HttpResponse<String> withPat = fx.http().post("/api/v1/admin/kafka/replay", pat, null, body);

    assertAll(
        // Kabul esigi: ayni kullanicinin JWT'si ile ayni uc KATI 200.
        () -> assertEquals(200, withJwt.statusCode(), "JWT ile admin ucu: " + withJwt.body()),
        () ->
            assertTrue(
                withJwt.body().contains("\"replayedCount\":0"),
                "bos konuda 0 replay beklenir: " + withJwt.body()),
        () -> assertEquals(403, withPat.statusCode(), "PAT ile admin ucu: " + withPat.body()));
  }

  private void createTopic(String topic) throws Exception {
    String bootstrap = ctx.getEnvironment().getProperty("spring.kafka.bootstrap-servers");
    try (AdminClient admin =
        AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
      admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
    }
  }

  // =========================================================================================
  // TOK-14 iptal / suresi dolan PAT
  // =========================================================================================

  @Test
  @DisplayName("TOK-14 | iptal edilmis PAT ile ilk istek 401 (oncesinde 200)")
  void tok14_revokedPatRejected() {
    PersonalAccessTokenService.IssuedToken issued =
        patService.create(admin.userId(), "tok14-revoke", null);
    String pat = issued.rawToken();
    assertEquals(
        200, fx.http().get(PROJECTS, pat, tenant.workspaceId(), null).statusCode(), "iptal oncesi");

    HttpResponse<String> revoke =
        fx.http().delete("/api/v1/access-tokens/" + issued.entity().getId(), admin, null, null);
    assertEquals(204, revoke.statusCode(), revoke.body());

    HttpResponse<String> after = fx.http().get(PROJECTS, pat, tenant.workspaceId(), null);
    assertEquals(401, after.statusCode(), "iptal sonrasi ilk istek: " + after.body());
  }

  @Test
  @DisplayName("TOK-14 | suresi dolmus PAT ile ilk istek 401 (oncesinde 200)")
  void tok14_expiredPatRejected() {
    String pat = patService.create(admin.userId(), "tok14-expire", Duration.ofDays(1)).rawToken();
    assertEquals(
        200,
        fx.http().get(PROJECTS, pat, tenant.workspaceId(), null).statusCode(),
        "sure dolmadan");

    assertEquals(
        1,
        execute(
            "UPDATE personal_access_tokens SET expires_at = now() - interval '1 minute'"
                + " WHERE token_hash = ?",
            sha256(pat)));

    HttpResponse<String> after = fx.http().get(PROJECTS, pat, tenant.workspaceId(), null);
    assertEquals(401, after.statusCode(), "sure dolduktan sonra: " + after.body());
  }

  // =========================================================================================
  // TOK-15 JWT/PAT ayristirmasi
  // =========================================================================================

  @Test
  @DisplayName("TOK-15 | wf_pat_ onekli bozuk degerler ve wf_pat_<JWT> 401, 500 degil")
  void tok15_malformedPatPrefixedValuesReturn401() {
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("kisa", "wf_pat_abc");
    cases.put("gecersiz karakter", "wf_pat_%%%!!!***");
    cases.put("4 KB", "wf_pat_" + "A".repeat(4096));
    cases.put("wf_pat_<gecerli JWT>", "wf_pat_" + admin.accessToken());

    List<Executable> checks = new ArrayList<>();
    cases.forEach(
        (label, token) ->
            checks.add(
                () -> {
                  HttpResponse<String> r =
                      fx.http().get(PROJECTS, token, tenant.workspaceId(), null);
                  assertEquals(401, r.statusCode(), label + " -> " + snippet(r));
                }));
    // 8 KB ayri vaka: Tomcat'in varsayilan 8 KB baslik siniri istegi uygulamaya ulasmadan reddeder
    // (konteyner davranisi), bu yuzden 401 degil 4xx (400 kabul) beklenir; 5xx ve govdede ic bilgi
    // olmamali. Uygulama katmaninin 401'i yukaridaki 4 KB vakasinda KATI sinanir.
    checks.add(
        () -> {
          HttpResponse<String> r =
              fx.http().get(PROJECTS, "wf_pat_" + "A".repeat(8192), tenant.workspaceId(), null);
          assertTrue(
              r.statusCode() >= 400 && r.statusCode() < 500, "8 KB: 4xx beklenir -> " + snippet(r));
          String b = r.body() == null ? "" : r.body();
          assertFalse(
              b.contains("Exception")
                  || b.contains("\tat ")
                  || b.contains("stackTrace")
                  || b.contains("com.app.tracker")
                  || b.contains("org.springframework")
                  || b.contains(".java:"),
              "8 KB govdede ic bilgi: " + snippet(r));
        });
    assertAll(checks);
  }

  @Test
  @DisplayName("TOK-15 | gecerli PAT 200, ayni kullanicinin JWT'si de 200 (kabul esigi)")
  void tok15_validPatAndJwtAccepted() {
    String pat = patService.create(admin.userId(), "tok15", null).rawToken();
    assertEquals(200, fx.http().get(PROJECTS, pat, tenant.workspaceId(), null).statusCode());
    assertEquals(200, fx.http().get(PROJECTS, admin, tenant.workspaceId(), null).statusCode());
    assertNotEquals(pat, admin.accessToken());
  }

  // =========================================================================================
  // Yardimcilar
  // =========================================================================================

  private static String snippet(HttpResponse<String> r) {
    String body = r.body() == null ? "" : r.body();
    return "durum=" + r.statusCode() + " govde=" + body.substring(0, Math.min(200, body.length()));
  }

  private static String sha256(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** app_migrator (tablo sahibi) ile duz JDBC: yazma. */
  private static int execute(String sql, Object... args) {
    try (Connection c = openMigratorConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, args);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** app_migrator ile duz JDBC: ilk satirin ilk sutunu. */
  private static String queryString(String sql, Object... args) {
    try (Connection c = openMigratorConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, args);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "satir yok: " + sql);
        return rs.getString(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      ps.setObject(i + 1, args[i]);
    }
  }
}
