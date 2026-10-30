package com.app.tracker.user;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Dilim 10: beklentiler sozlesmeden, gercek HTTP ve users tablosu uzerinden dogrulanir. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrivacyNoticeIntegrationTest extends AbstractIntegrationTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  // Sozlesmedeki degerler sabittir; beklenen surum uretim sabitinden turetilmez.
  private static final String CURRENT_VERSION = "2026-10-29";
  private static final String OLD_VERSION = "2000-01-01";
  private static final String PASSWORD = "correct-horse-battery";
  private static final String REGISTER = "/api/v1/auth/register";
  private static final String ME = "/api/v1/me";
  private static final String ACCEPTANCE = ME + "/privacy-notice-acceptance";
  private static final Instant OLD_ACCEPTED_AT = Instant.parse("2000-01-01T12:00:00Z");

  @LocalServerPort private int port;
  @Autowired private AuthService authService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MutableClock clock;

  private Instant now;

  private record Actor(String email, String jwt) {}

  private record Notice(String version, Instant acceptedAt) {}

  @BeforeEach
  void setClock() {
    now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    clock.set(now);
  }

  @Test
  void K1_validRegistrationPersistsCurrentVersionAndClockTime() throws Exception {
    String email = newEmail();

    assertRegistrationAccepted(email);
    assertEquals(new Notice(CURRENT_VERSION, now), notice(email));
  }

  @Test
  void K2_registeredAndLoggedInUserSeesPersistedNoticeInMe() throws Exception {
    Actor actor = newActor();

    assertMeNotice(actor, new Notice(CURRENT_VERSION, now));
    assertEquals(notice(actor.email()), meNotice(actor.jwt()));
  }

  @Test
  void K3_jwtAcceptsPreviouslyNullNoticeWithoutWorkspace() throws Exception {
    Actor actor = newActor();
    setNotice(actor, null, null);
    assertMeNotice(actor, new Notice(null, null));

    assertStatus(204, accept(actor.jwt(), CURRENT_VERSION));

    assertMeNotice(actor, new Notice(CURRENT_VERSION, now));
  }

  @Test
  void K4_secondAcceptanceOfSameVersionPreservesFirstTimestamp() throws Exception {
    Actor actor = newActor();
    setNotice(actor, null, null);
    assertStatus(204, accept(actor.jwt(), CURRENT_VERSION));
    Notice first = notice(actor.email());
    assertEquals(new Notice(CURRENT_VERSION, now), first);

    // Ikinci cagrinin saati farklidir: kosulsuz yeniden yazma kesin olarak yakalanir.
    clock.set(now.plusSeconds(1));
    assertStatus(204, accept(actor.jwt(), CURRENT_VERSION));

    assertMeNotice(actor, first);
  }

  @Test
  void K5_currentAcceptanceReplacesOldVersionAndTimestamp() throws Exception {
    Actor actor = newActor();
    setNotice(actor, OLD_VERSION, OLD_ACCEPTED_AT);
    assertMeNotice(actor, new Notice(OLD_VERSION, OLD_ACCEPTED_AT));

    assertStatus(204, accept(actor.jwt(), CURRENT_VERSION));

    assertMeNotice(actor, new Notice(CURRENT_VERSION, now));
  }

  @Test
  void R1_falseAcceptanceIs422AndCreatesNoUserThenTrueSucceeds() throws Exception {
    String email = newEmail();
    Map<String, Object> body = registration(email);
    body.put("privacyNoticeAccepted", false);

    assertRegistrationRejectedThenAccepted(email, body, 422);
  }

  @Test
  void R2_missingOrNullAcceptanceIs422AndCreatesNoUserThenTrueSucceeds() throws Exception {
    String missingEmail = newEmail();
    Map<String, Object> missing = registration(missingEmail);
    missing.remove("privacyNoticeAccepted");
    assertRegistrationRejectedThenAccepted(missingEmail, missing, 422);

    String nullEmail = newEmail();
    Map<String, Object> explicitNull = registration(nullEmail);
    explicitNull.put("privacyNoticeAccepted", null);
    assertRegistrationRejectedThenAccepted(nullEmail, explicitNull, 422);
  }

  @Test
  void R3_missingBlankNullOrOverlongVersionIs422AndCreatesNoUser() throws Exception {
    String missingEmail = newEmail();
    Map<String, Object> missing = registration(missingEmail);
    missing.remove("privacyNoticeVersion");
    assertRegistrationRejectedThenAccepted(missingEmail, missing, 422);

    for (String invalid : List.of("", " \t ", "v".repeat(21))) {
      String email = newEmail();
      Map<String, Object> body = registration(email);
      body.put("privacyNoticeVersion", invalid);
      assertRegistrationRejectedThenAccepted(email, body, 422);
    }

    String nullEmail = newEmail();
    Map<String, Object> explicitNull = registration(nullEmail);
    explicitNull.put("privacyNoticeVersion", null);
    assertRegistrationRejectedThenAccepted(nullEmail, explicitNull, 422);
  }

  @Test
  void R4_outdatedVersionIs400BeforeEmailUniquenessAndCreatesNoUser() throws Exception {
    for (String outdated : List.of(OLD_VERSION, "v".repeat(20))) {
      String email = newEmail();
      Map<String, Object> body = registration(email);
      body.put("privacyNoticeVersion", outdated);
      assertRegistrationRejectedThenAccepted(email, body, 400);

      // Ayni e-posta artik kayitli; surum hatasi tekillik hatasindan once gelmeli.
      Map<String, Object> before = userRow(email);
      HttpResponse<String> duplicate = post(REGISTER, null, body);
      assertStatus(400, duplicate);
      assertTrue(
          duplicate
              .body()
              .contains("Aydinlatma metni guncellendi; sayfayi yenileyip yeniden onaylayin."),
          duplicate.body());
      assertEquals(before, userRow(email));
      assertUserCount(email, 1);
    }
  }

  @Test
  void R5_outdatedAcceptanceIs400AndLeavesNullOldAndCurrentRecordsUnchanged() throws Exception {
    Actor actor = newActor();
    for (Notice initial :
        List.of(
            new Notice(null, null),
            new Notice(OLD_VERSION, OLD_ACCEPTED_AT),
            new Notice(CURRENT_VERSION, OLD_ACCEPTED_AT))) {
      setNotice(actor, initial.version(), initial.acceptedAt());
      Map<String, Object> before = userRow(actor.email());

      assertStatus(400, accept(actor.jwt(), OLD_VERSION));
      assertEquals(before, userRow(actor.email()));
      assertMeNotice(actor, initial);

      assertStatus(204, accept(actor.jwt(), CURRENT_VERSION));
      Instant expectedTime = CURRENT_VERSION.equals(initial.version()) ? OLD_ACCEPTED_AT : now;
      assertMeNotice(actor, new Notice(CURRENT_VERSION, expectedTime));
    }
  }

  @Test
  void R6_missingBlankNullOrOverlongAcceptanceIs422AndBodylessIs4xxWithoutChanges()
      throws Exception {
    Actor actor = newActor();
    setNotice(actor, OLD_VERSION, OLD_ACCEPTED_AT);
    Map<String, Object> before = userRow(actor.email());

    assertStatus(422, post(ACCEPTANCE, actor.jwt(), Map.of()));
    assertEquals(before, userRow(actor.email()));
    for (String invalid : List.of("", " \t ", "v".repeat(21))) {
      assertStatus(422, accept(actor.jwt(), invalid));
      assertEquals(before, userRow(actor.email()));
    }
    Map<String, Object> explicitNull = new LinkedHashMap<>();
    explicitNull.put("version", null);
    assertStatus(422, post(ACCEPTANCE, actor.jwt(), explicitNull));
    assertEquals(before, userRow(actor.email()));

    HttpResponse<String> bodyless = post(ACCEPTANCE, actor.jwt(), null);
    assertTrue(bodyless.statusCode() >= 400 && bodyless.statusCode() < 500, bodyless.body());
    assertEquals(before, userRow(actor.email()));

    // 20 karakter bean validation'dan gecer; guncel olmayan surum is kuraliyla 400 olur.
    assertStatus(400, accept(actor.jwt(), "v".repeat(20)));
    assertEquals(before, userRow(actor.email()));
    assertStatus(204, accept(actor.jwt(), CURRENT_VERSION));
    assertMeNotice(actor, new Notice(CURRENT_VERSION, now));
  }

  @Test
  void R7_validPatIs403WithoutChangesButSameUserJwtAccepts() throws Exception {
    Actor actor = newActor();
    String pat = createPat(actor);
    setNotice(actor, null, null);
    Map<String, Object> before = userRow(actor.email());
    // PAT gercekten kimlik dogrular; baska bir nedenle reddedilmesi testi gecirmemeli.
    assertEquals(new Notice(null, null), meNotice(pat));

    assertStatus(403, accept(pat, CURRENT_VERSION));
    assertEquals(before, userRow(actor.email()));

    assertStatus(204, accept(actor.jwt(), CURRENT_VERSION));
    assertMeNotice(actor, new Notice(CURRENT_VERSION, now));
  }

  @Test
  void R8_anonymousAcceptanceIs401ButAuthenticatedRequestAccepts() throws Exception {
    Actor actor = newActor();
    setNotice(actor, null, null);
    Map<String, Object> before = userRow(actor.email());

    assertStatus(401, accept(null, CURRENT_VERSION));
    assertEquals(before, userRow(actor.email()));

    assertStatus(204, accept(actor.jwt(), CURRENT_VERSION));
    assertMeNotice(actor, new Notice(CURRENT_VERSION, now));
  }

  private Map<String, Object> registration(String email) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("email", email);
    body.put("password", PASSWORD);
    body.put("fullName", "Privacy Notice User");
    body.put("privacyNoticeAccepted", true);
    body.put("privacyNoticeVersion", CURRENT_VERSION);
    return body;
  }

  private void assertRegistrationRejectedThenAccepted(
      String email, Map<String, Object> body, int status) throws Exception {
    HttpResponse<String> response = post(REGISTER, null, body);
    assertAll(() -> assertStatus(status, response), () -> assertUserCount(email, 0));
    // Yalniz reddedilen alan duzeltilir; ayni e-posta/ad/parola ile kabul esidir.
    assertRegistrationAccepted(email);
  }

  private void assertRegistrationAccepted(String email) throws Exception {
    assertStatus(201, post(REGISTER, null, registration(email)));
    assertUserCount(email, 1);
    assertEquals(new Notice(CURRENT_VERSION, now), notice(email));
  }

  private Actor newActor() throws Exception {
    String email = newEmail();
    assertRegistrationAccepted(email);
    String jwt = authService.login(email, PASSWORD, "127.0.0.1").accessToken();
    return new Actor(email, jwt);
  }

  private String createPat(Actor actor) throws Exception {
    HttpResponse<String> response =
        post("/api/v1/access-tokens", actor.jwt(), Map.of("name", "privacy-notice-contract"));
    assertStatus(201, response);
    String pat = objectMapper.readTree(response.body()).get("rawToken").asText();
    assertTrue(pat.startsWith("wf_pat_"), response.body());
    return pat;
  }

  private void assertUserCount(String email, long expected) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM users WHERE email = ?", Long.class, email);
    assertEquals(Long.valueOf(expected), count, email);
  }

  private Map<String, Object> userRow(String email) {
    return jdbcTemplate.queryForMap("SELECT * FROM users WHERE email = ?", email);
  }

  private Notice notice(String email) {
    return jdbcTemplate.queryForObject(
        "SELECT privacy_notice_version, privacy_notice_accepted_at FROM users WHERE email = ?",
        (rs, rowNum) -> {
          Timestamp acceptedAt = rs.getTimestamp("privacy_notice_accepted_at");
          return new Notice(
              rs.getString("privacy_notice_version"),
              acceptedAt == null ? null : acceptedAt.toInstant());
        },
        email);
  }

  private void setNotice(Actor actor, String version, Instant acceptedAt) {
    assertEquals(
        1,
        jdbcTemplate.update(
            "UPDATE users SET privacy_notice_version = ?, privacy_notice_accepted_at = ?"
                + " WHERE email = ?",
            version,
            acceptedAt == null ? null : Timestamp.from(acceptedAt),
            actor.email()));
    assertEquals(new Notice(version, acceptedAt), notice(actor.email()));
  }

  private void assertMeNotice(Actor actor, Notice expected) throws Exception {
    assertEquals(expected, notice(actor.email()));
    assertEquals(expected, meNotice(actor.jwt()));
  }

  private Notice meNotice(String bearer) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url(ME)))
            .header("Authorization", "Bearer " + bearer)
            .GET()
            .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    assertStatus(200, response);
    JsonNode body = objectMapper.readTree(response.body());
    JsonNode version = body.get("privacyNoticeVersion");
    JsonNode acceptedAt = body.get("privacyNoticeAcceptedAt");
    assertNotNull(version, response.body());
    assertNotNull(acceptedAt, response.body());
    if (version.isNull()) {
      assertTrue(acceptedAt.isNull(), response.body());
      return new Notice(null, null);
    }
    assertTrue(version.isString(), response.body());
    assertTrue(acceptedAt.isString(), response.body());
    return new Notice(version.asText(), Instant.parse(acceptedAt.asText()));
  }

  private HttpResponse<String> accept(String bearer, String version) throws Exception {
    return post(ACCEPTANCE, bearer, Map.of("version", version));
  }

  private HttpResponse<String> post(String path, String bearer, Map<String, Object> body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url(path)))
            .header("Content-Type", "application/json")
            .POST(
                body == null
                    ? BodyPublishers.noBody()
                    : BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    // Benzersiz istemci IP'si: kayit ucunun IP basina hiz siniri (SEC-07) paylasilan Redis'te
    // diger testlerin 127.0.0.1 butcesinden etkilenmesin.
    request.header("X-Forwarded-For", uniqueIp());
    // Hicbir cagrida X-Workspace-Id yoktur; bu uclar workspace gerektirmez.
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String uniqueIp() {
    java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
    return "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private static String newEmail() {
    return "privacy-notice-" + UUID.randomUUID() + "@tracker.local";
  }

  private static void assertStatus(int expected, HttpResponse<String> response) {
    assertEquals(expected, response.statusCode(), response.body());
  }

  /**
   * Mockito ile Clock mock'u kullanilmaz: zamanlanmis isler ayni bean'i baska thread'den cagirir ve
   * stub kurulumu sirasinda {@code WrongTypeOfReturnValue} uretir. Thread-guvenli sabit saat.
   */
  @TestConfiguration
  static class ClockOverride {
    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }
  }

  static final class MutableClock extends Clock {
    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    private volatile Instant instant = Instant.now();

    void set(Instant value) {
      this.instant = value;
    }

    @Override
    public ZoneId getZone() {
      return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
