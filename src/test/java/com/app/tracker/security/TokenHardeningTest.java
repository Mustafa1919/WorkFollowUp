package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.security.JwtKeyProvider;
import com.app.tracker.security.support.JwtForge;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityHttp;
import com.app.tracker.security.support.SecurityMail;
import com.app.tracker.user.model.PrivacyNotice;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guvenlik katalogu §1-§2: kimlik dogrulama, oturum ve token sertlestirme (AUTH-02/04/07/08/09,
 * TOK-01..05, TOK-09). Gercek servlet uzerinden; beklenen degerler katalogdan/sozlesmeden gelir.
 * DB'ye dogrudan erisim {@code app_migrator} ile duz JDBC'dir (token tarihlerini geriye cekmek ve
 * hash'leri okumak icin).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenHardeningTest extends AbstractIntegrationTest {

  private static final String PASSWORD = SecurityFixture.PASSWORD;
  private static final String NEW_PASSWORD = "brand-new-horse-staple-1";

  /** Hesap kilidi esigi (BruteForceGuard.USER_LOCK_THRESHOLD ile ayni, sozlesmedeki "esik"). */
  private static final int LOCK_THRESHOLD = 10;

  @LocalServerPort private int port;
  @Autowired private AuthService authService;
  @Autowired private JwtKeyProvider keyProvider;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper mapper;

  private SecurityHttp http;
  private JwtForge forge;
  private SecurityMail mail;

  @BeforeEach
  void setUp() {
    http = new SecurityHttp(port);
    forge = new JwtForge(keyProvider);
    mail = new SecurityMail(path -> mailpitApiUrl(path), mapper);
  }

  private record User(UUID id, String email) {}

  // =========================================================================================
  // AUTH-02 hesap kilidi
  // =========================================================================================

  @Test
  @DisplayName(
      "AUTH-02 | esik kadar yanlis parola sonrasi hesap kilitlenir, dogru parola da reddedilir")
  void auth02_lockedAccountRejectsCorrectPassword() {
    User user = newUser("auth02r");
    // clearThrottle yalniz KILIT yolunu yalitmak icin: ussel gecikme denemeleri yavaslatip esige
    // ulasmayi engeller; gecikmenin kendisi auth02_exponentialDelay testinde silinmeden sinanir.
    for (int i = 1; i <= LOCK_THRESHOLD; i++) {
      clearThrottle(user.email());
      HttpResponse<String> wrong = httpLogin(user.email(), "wrong-password-" + i);
      assertTrue(is4xx(wrong), "yanlis parola " + i + ": " + wrong.statusCode());
    }
    clearThrottle(user.email());
    HttpResponse<String> correct = httpLogin(user.email(), PASSWORD);
    assertTrue(
        is4xx(correct),
        "kilit sonrasi dogru parola reddedilmeli, gozlenen " + correct.statusCode());
    assertFalse(correct.body().contains("accessToken"), "kilitli hesaba token verildi");

    clearThrottle(user.email());
    assertTrue(is4xx(httpLogin(user.email(), PASSWORD)), "kilit kalici olmali (ikinci deneme)");
  }

  @Test
  @DisplayName(
      "AUTH-02 | ussel gecikme: pencere icinde dogru parola reddedilir, pencere buyur, sonra kabul")
  void auth02_exponentialDelayRejectsWithinWindowAndGrows() throws Exception {
    User user = newUser("auth02d");
    // Gecikme anahtari SILINMEZ. Ilk iki yanlis deneme arka arkaya yapilir (ilki gecikmesiz).
    assertTrue(is4xx(httpLogin(user.email(), "wrong-password-1")));
    assertTrue(is4xx(httpLogin(user.email(), "wrong-password-2")));

    // Olcum 1: ~2 sn'lik pencere icinde dogru parola reddedilir.
    HttpResponse<String> inWindow1 = httpLogin(user.email(), PASSWORD);
    assertTrue(
        is4xx(inWindow1),
        "gecikme penceresi (1) icinde dogru parola reddedilmeli, gozlenen "
            + inWindow1.statusCode());

    // Ilk pencere dolunca ucuncu yanlis deneme kabul edilip sayilir; pencere buyur.
    Thread.sleep(2_500);
    assertTrue(is4xx(httpLogin(user.email(), "wrong-password-3")));

    // Olcum 2: ilk pencere kadar (2,5 sn) beklemek yetmez, pencere buyumus olmali.
    HttpResponse<String> immediate = httpLogin(user.email(), PASSWORD);
    Thread.sleep(2_500);
    HttpResponse<String> stillInGrownWindow = httpLogin(user.email(), PASSWORD);
    assertAll(
        () ->
            assertTrue(
                is4xx(immediate),
                "gecikme penceresi (2) icinde dogru parola reddedilmeli, gozlenen "
                    + immediate.statusCode()),
        () ->
            assertTrue(
                is4xx(stillInGrownWindow),
                "pencere ardisik basarisizlikta buyumeli: 2,5 sn sonra hala reddedilmeli, gozlenen "
                    + stillInGrownWindow.statusCode()));

    // K2: pencere dolduktan sonra dogru parola kabul edilir.
    Thread.sleep(2_500);
    HttpResponse<String> afterWindow = httpLogin(user.email(), PASSWORD);
    assertEquals(200, afterWindow.statusCode(), "pencere sonrasi: " + afterWindow.body());
  }

  @Test
  @DisplayName("AUTH-02 | esik altinda dogru parola ile giris basarili (kabul esigi)")
  void auth02_belowThresholdLoginSucceeds() {
    User user = newUser("auth02k");
    for (int i = 1; i <= 3; i++) {
      clearThrottle(user.email());
      assertTrue(is4xx(httpLogin(user.email(), "wrong-password-" + i)));
    }
    clearThrottle(user.email());
    HttpResponse<String> ok = httpLogin(user.email(), PASSWORD);
    assertEquals(200, ok.statusCode(), ok.body());
    assertTrue(ok.body().contains("accessToken"));
  }

  // =========================================================================================
  // AUTH-04 e-posta tekilligi / normalizasyon
  // =========================================================================================

  @Test
  @DisplayName("AUTH-04 | gercekten farkli e-postayla kayit basarili (kabul esigi)")
  void auth04_differentEmailRegisters() throws Exception {
    String a = "sec-auth04k-a-" + UUID.randomUUID() + "@tracker.local";
    String b = "sec-auth04k-b-" + UUID.randomUUID() + "@tracker.local";
    assertEquals(201, httpRegister(a).statusCode());
    assertEquals(201, httpRegister(b).statusCode());
  }

  // =========================================================================================
  // AUTH-07 parola sifirlama token'i
  // =========================================================================================

  @Test
  @DisplayName("AUTH-07 | taze sifirlama token'i kabul, ikinci kullanim reddedilir")
  void auth07_resetTokenIsSingleUse() {
    User user = newUser("auth07a");
    String token = requestResetToken(user);

    HttpResponse<String> first = confirmReset(token, NEW_PASSWORD);
    assertEquals(204, first.statusCode(), "taze token kabul (kabul esigi): " + first.body());
    assertEquals(
        200, httpLogin(user.email(), NEW_PASSWORD).statusCode(), "yeni parolayla giris olmali");

    HttpResponse<String> second = confirmReset(token, "another-pass-phrase-99");
    assertTrue(is4xx(second), "ikinci kullanim reddedilmeli, gozlenen " + second.statusCode());
    clearThrottle(user.email());
    assertEquals(
        200,
        httpLogin(user.email(), NEW_PASSWORD).statusCode(),
        "ikinci kullanim parolayi degistirmemeli");
  }

  @Test
  @DisplayName("AUTH-07 | 30 dakikadan eski sifirlama token'i reddedilir; omur en fazla 30 dk")
  void auth07_expiredResetTokenRejected() throws Exception {
    User user = newUser("auth07b");
    String token = requestResetToken(user);
    long secondsLeft =
        queryLong(
            "SELECT EXTRACT(EPOCH FROM (expires_at - now()))::bigint FROM verification_tokens"
                + " WHERE token_hash = ?",
            sha256(token));
    assertTrue(secondsLeft > 0 && secondsLeft <= 30 * 60, "kalan omur (sn): " + secondsLeft);

    execute(
        "UPDATE verification_tokens SET expires_at = expires_at - interval '31 minutes',"
            + " created_at = created_at - interval '31 minutes' WHERE token_hash = ?",
        sha256(token));
    HttpResponse<String> response = confirmReset(token, NEW_PASSWORD);
    assertTrue(
        is4xx(response), "30 dk'dan eski token reddedilmeli, gozlenen " + response.statusCode());
    assertEquals(200, httpLogin(user.email(), PASSWORD).statusCode(), "parola degismemeli");
  }

  // =========================================================================================
  // AUTH-08 parola degisiminde oturumlar sonlanir
  // =========================================================================================

  @Test
  @DisplayName("AUTH-08 | sifirlama oncesi alinmis refresh token sifirlama sonrasi gecersiz")
  void auth08_oldRefreshTokenDiesAfterPasswordReset() {
    User user = newUser("auth08");
    String oldRefresh = authService.login(user.email(), PASSWORD, "127.0.0.1").refreshToken();

    String token = requestResetToken(user);
    assertEquals(204, confirmReset(token, NEW_PASSWORD).statusCode());

    HttpResponse<String> stale = refresh(oldRefresh);
    assertTrue(is4xx(stale), "eski refresh token reddedilmeli, gozlenen " + stale.statusCode());

    // Kabul esigi: sifirlama sonrasi yeni login'in refresh'i calisir.
    String fresh = authService.login(user.email(), NEW_PASSWORD, "127.0.0.1").refreshToken();
    assertEquals(200, refresh(fresh).statusCode());
  }

  // =========================================================================================
  // AUTH-09 e-posta dogrulama token'i
  // =========================================================================================

  @Test
  @DisplayName("AUTH-09 | taze dogrulama token'i kabul, tekrar kullanim reddedilir")
  void auth09_verificationTokenIsSingleUse() throws Exception {
    User user = newUser("auth09a");
    String token = mail.rawToken(user.email(), "dogrula");

    HttpResponse<String> first = verifyEmail(token);
    assertEquals(204, first.statusCode(), "taze token kabul (kabul esigi): " + first.body());
    assertEquals("t", queryString("SELECT email_verified FROM users WHERE id = ?", user.id()));

    HttpResponse<String> second = verifyEmail(token);
    assertTrue(is4xx(second), "tekrar kullanim reddedilmeli, gozlenen " + second.statusCode());
  }

  @Test
  @DisplayName("AUTH-09 | 24 saatten eski dogrulama token'i reddedilir; omur en fazla 24 saat")
  void auth09_expiredVerificationTokenRejected() throws Exception {
    User user = newUser("auth09b");
    String token = mail.rawToken(user.email(), "dogrula");
    long secondsLeft =
        queryLong(
            "SELECT EXTRACT(EPOCH FROM (expires_at - now()))::bigint FROM verification_tokens"
                + " WHERE token_hash = ?",
            sha256(token));
    assertTrue(secondsLeft > 0 && secondsLeft <= 24 * 3600, "kalan omur (sn): " + secondsLeft);

    execute(
        "UPDATE verification_tokens SET expires_at = expires_at - interval '25 hours',"
            + " created_at = created_at - interval '25 hours' WHERE token_hash = ?",
        sha256(token));
    HttpResponse<String> response = verifyEmail(token);
    assertAll(
        () ->
            assertTrue(
                is4xx(response),
                "24 saatten eski token reddedilmeli, gozlenen " + response.statusCode()),
        () ->
            assertEquals(
                "f",
                queryString("SELECT email_verified FROM users WHERE id = ?", user.id()),
                "e-posta dogrulanmis isaretlenmemeli"));
  }

  // =========================================================================================
  // TOK-01 imza dogrulama
  // =========================================================================================

  @Test
  @DisplayName("TOK-01 | bozulmus imza, alg:none ve HS256(acik anahtar sirri) token'lari 401")
  void tok01_forgedTokensRejected() {
    User user = newUser("tok01r");
    String valid = authService.login(user.email(), PASSWORD, "127.0.0.1").accessToken();
    String[] parts = valid.split("\\.");

    String tamperedSignature =
        parts[0]
            + "."
            + parts[1]
            + "."
            + (parts[2].charAt(0) == 'A' ? "B" : "A")
            + parts[2].substring(1);
    String otherSub = UUID.randomUUID().toString();
    String tamperedPayload =
        parts[0]
            + "."
            + JwtForge.b64(JwtForge.payloadJson(otherSub, futureEpoch()))
            + "."
            + parts[2];
    String payload = JwtForge.payloadJson(user.id().toString(), futureEpoch());

    Map<String, String> attacks = new LinkedHashMap<>();
    attacks.put("imza degistirilmis", tamperedSignature);
    attacks.put("payload degistirilmis, imza eski", tamperedPayload);
    attacks.put("alg:none (bos imza bolumu)", JwtForge.unsigned(payload));
    attacks.put(
        "alg:none (imza bolumu hic yok)", JwtForge.unsigned(payload).replaceAll("\\.$", ""));
    attacks.put("HS256, DER acik anahtar sirri", forge.hs256WithPublicKeyAsSecret(payload));

    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    attacks.forEach(
        (label, token) ->
            checks.add(
                () -> {
                  HttpResponse<String> r = http.get("/api/v1/me", token, null, null);
                  assertEquals(401, r.statusCode(), label + " -> " + snippet(r));
                }));
    assertAll(checks);
  }

  @Test
  @DisplayName("TOK-01 | gecerli token 200 (kabul esigi)")
  void tok01_validTokenAccepted() {
    User user = newUser("tok01k");
    String valid = authService.login(user.email(), PASSWORD, "127.0.0.1").accessToken();
    assertEquals(200, http.get("/api/v1/me", valid, null, null).statusCode());
  }

  // =========================================================================================
  // TOK-02 suresi dolmus token
  // =========================================================================================

  @Test
  @DisplayName("TOK-02 | exp gecmiste olan (gecerli imzali) token 401; exp gelecekteki esi 200")
  void tok02_expiredTokenRejected() {
    User user = newUser("tok02");
    long now = System.currentTimeMillis() / 1000;
    String expired = forge.signRs256(JwtForge.payloadJson(user.id().toString(), now - 60));
    String fresh = forge.signRs256(JwtForge.payloadJson(user.id().toString(), now + 600));

    assertEquals(
        200,
        http.get("/api/v1/me", fresh, null, null).statusCode(),
        "ayni anahtarla imzali gecerli exp'li token kabul edilmeli (kabul esigi)");
    HttpResponse<String> r = http.get("/api/v1/me", expired, null, null);
    assertEquals(401, r.statusCode(), snippet(r));
  }

  // =========================================================================================
  // TOK-03 logout
  // =========================================================================================

  @Test
  @DisplayName("TOK-03 | logout sonrasi ayni access token 401 (oncesinde 200)")
  void tok03_accessTokenDeadAfterLogout() {
    User user = newUser("tok03");
    AuthService.LoginResult login = authService.login(user.email(), PASSWORD, "127.0.0.1");

    assertEquals(
        200, http.get("/api/v1/me", login.accessToken(), null, null).statusCode(), "logout oncesi");

    HttpResponse<String> logout =
        http.send(
            "POST",
            "/api/v1/auth/logout",
            null,
            null,
            null,
            Map.of("Cookie", "refresh_token=" + login.refreshToken()));
    assertEquals(204, logout.statusCode());

    HttpResponse<String> after = http.get("/api/v1/me", login.accessToken(), null, null);
    assertEquals(401, after.statusCode(), "logout sonrasi " + snippet(after));
  }

  // =========================================================================================
  // TOK-04 bozuk biçimli token
  // =========================================================================================

  @Test
  @DisplayName("TOK-04 | bozuk bicimli her girdi 401 doner, 500 degil, govdede stack trace yok")
  void tok04_malformedTokenReturns401() {
    User user = newUser("tok04");
    long exp = futureEpoch();
    String sub = user.id().toString();
    String validPayload = JwtForge.payloadJson(sub, exp);

    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("gecersiz base64", "abc!!.def@@.ghi##");
    cases.put("2 parcali", "aaaa.bbbb");
    cases.put("4 parcali", "aaaa.bbbb.cccc.dddd");
    cases.put(
        "exp eksik",
        forge.signRs256(
            "{\"sub\":\""
                + sub
                + "\",\"roles\":[\"USER\"],\"jti\":\""
                + UUID.randomUUID()
                + "\"}"));
    cases.put(
        "exp string",
        forge.signRs256(
            "{\"sub\":\""
                + sub
                + "\",\"roles\":[\"USER\"],\"jti\":\""
                + UUID.randomUUID()
                + "\",\"exp\":\"9999999999\"}"));
    cases.put(
        "sub UUID degil",
        forge.signRs256(
            "{\"sub\":\"not-a-uuid\",\"roles\":[\"USER\"],\"jti\":\""
                + UUID.randomUUID()
                + "\",\"exp\":"
                + exp
                + "}"));
    cases.put(
        "roles eksik (ek vaka)",
        forge.signRs256(
            "{\"sub\":\"" + sub + "\",\"jti\":\"" + UUID.randomUUID() + "\",\"exp\":" + exp + "}"));
    // Gecerli RSA imzali ama govdesi JSON olmayan payload: imza asamasini gecip JSON cozumlemesine
    // ulasir.
    cases.put("payload JSON degil (ek vaka)", forge.signRs256("not json at all"));
    cases.put("bos Bearer", "");
    cases.put("4 KB cop", "A".repeat(4096));

    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    cases.forEach(
        (label, token) ->
            checks.add(
                () -> {
                  HttpResponse<String> r = http.get("/api/v1/me", token, null, null);
                  assertEquals(401, r.statusCode(), label + " -> " + snippet(r));
                  assertFalse(leaksInternals(r.body()), label + " govdede ic bilgi: " + snippet(r));
                }));
    // 8 KB cop ayri vaka: Tomcat'in varsayilan 8 KB baslik siniri istegi uygulamaya ulasmadan
    // reddeder
    // (konteyner davranisi), bu yuzden 401 degil 4xx (400 kabul) beklenir; 5xx ve govdede ic bilgi
    // olmamali. Uygulama katmaninin 401'i yukaridaki 4 KB vakasinda KATI sinanir.
    checks.add(
        () -> {
          HttpResponse<String> r = http.get("/api/v1/me", "A".repeat(8192), null, null);
          assertTrue(is4xx(r), "8 KB cop: 4xx beklenir -> " + snippet(r));
          assertFalse(leaksInternals(r.body()), "8 KB cop govdede ic bilgi: " + snippet(r));
        });
    assertAll(checks);

    // Kabul esigi: ayni kullanici icin gecerli token 200.
    assertEquals(
        200, http.get("/api/v1/me", forge.signRs256(validPayload), null, null).statusCode());
  }

  // =========================================================================================
  // TOK-05 refresh rotasyonu + yeniden kullanim tespiti
  // =========================================================================================

  @Test
  @DisplayName("TOK-05 K1 | normal rotasyon zinciri r1->r2->r3 calisir (kabul esigi)")
  void tok05_K1_normalRotationChainWorks() {
    User user = newUser("tok05k");
    String r1 = authService.login(user.email(), PASSWORD, "127.0.0.1").refreshToken();

    HttpResponse<String> x1 = refresh(r1);
    assertEquals(200, x1.statusCode(), x1.body());
    String r2 = refreshCookie(x1);
    HttpResponse<String> x2 = refresh(r2);
    assertEquals(200, x2.statusCode(), x2.body());
    String r3 = refreshCookie(x2);

    assertEquals(3, new TreeSet<>(Set.of(r1, r2, r3)).size(), "her rotasyon yeni token uretmeli");
    assertEquals(200, refresh(r3).statusCode());
  }

  @Test
  @DisplayName("TOK-05 K2 | grace icinde aile iptal edilmez ve guvenlik uyarisi yazilmaz")
  void tok05_K2_reuseWithinGraceIsNotRejected() {
    User user = newUser("tok05g");
    String r1 = authService.login(user.email(), PASSWORD, "127.0.0.1").refreshToken();
    assertEquals(200, refresh(r1).statusCode());

    HttpResponse<String> again = refresh(r1);
    assertEquals(200, again.statusCode(), "grace icinde tekrar kullanim: " + again.body());
    UUID familyId = refreshFamily(r1);
    assertEquals(
        0L,
        queryLong("SELECT count(*) FROM refresh_tokens WHERE family_id = ? AND revoked", familyId),
        "K2: grace icinde ailenin hicbir token'i iptal edilmemeli");
    assertEquals(0L, reuseAlertCount(user), "K2: grace icinde reuse uyarisi yazilmamali");
  }

  @Test
  @DisplayName("TOK-05 R1 R3 | grace disinda tekrar kullanim 401 ve en yeni refresh 400")
  void tok05_R1_R3_reuseOutsideGraceRevokesFamily() throws Exception {
    User user = newUser("tok05r");
    String r1 = authService.login(user.email(), PASSWORD, "127.0.0.1").refreshToken();
    String r2 = refreshCookie(refresh(r1));
    String r3 = refreshCookie(refresh(r2));
    // Zincir saglam: en yeni token (r3) henuz kullanilabilir durumda.
    assertNotNull(r3);

    // r1 grace'i asmis olsun: used_at 60 sn geriye.
    assertEquals(
        1,
        execute(
            "UPDATE refresh_tokens SET used_at = used_at - interval '60 seconds'"
                + " WHERE token_hash = ?",
            sha256(r1)));

    HttpResponse<String> replay = refresh(r1);
    HttpResponse<String> newest = refresh(r3);
    assertAll(
        () ->
            assertEquals(
                401, replay.statusCode(), "grace disi tekrar kullanim: " + snippet(replay)),
        () -> assertEquals(400, newest.statusCode(), "R3: " + snippet(newest)),
        () ->
            assertEquals(
                "Gecersiz refresh token.",
                mapper.readTree(newest.body()).path("detail").asString("")),
        () ->
            assertTrue(
                replay
                    .headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .startsWith("application/problem+json"),
                "R1: ProblemDetail icerik turu"),
        () -> assertEquals(401, mapper.readTree(replay.body()).path("status").asInt()),
        () ->
            assertEquals(
                "Guvenlik ihlali tespit edildi, tum oturumlar sonlandirildi. Lutfen yeniden giris yapin.",
                mapper.readTree(replay.body()).path("detail").asString("")));
  }

  @Test
  @DisplayName("TOK-05 R2 | ailedeki tum iptaller ret yanitindan sonra ayri baglantida gorunur")
  void tok05_R2_familyRevocationCommitsDespiteRejection() {
    User user = newUser("tok05r2");
    Rotation chain = rotateTwice(user);
    UUID familyId = refreshFamily(chain.r1());
    assertEquals(
        3L, queryLong("SELECT count(*) FROM refresh_tokens WHERE family_id = ?", familyId));
    assertEquals(
        0L,
        queryLong("SELECT count(*) FROM refresh_tokens WHERE family_id = ? AND revoked", familyId),
        "R2 kabul esigi: reuse oncesi tum aile etkin");

    expireReuseGrace(chain.r1());
    assertEquals(401, refresh(chain.r1()).statusCode());

    // Her queryLong yeni JDBC baglantisi acar; servis persistence context'i okunmaz.
    assertAll(
        () ->
            assertEquals(
                3L,
                queryLong("SELECT count(*) FROM refresh_tokens WHERE family_id = ?", familyId),
                "R2: aile satirlari silinmemeli"),
        () ->
            assertEquals(
                3L,
                queryLong(
                    "SELECT count(*) FROM refresh_tokens WHERE family_id = ? AND revoked",
                    familyId),
                "R2: r1, r2 ve r3 iptalleri commit edilmeli"));
  }

  @Test
  @DisplayName("TOK-05 R4 | ayni access token reuse oncesi 200, aile iptali sonrasi 401")
  void tok05_R4_familyAccessTokenIsRejectedAfterReuse() throws Exception {
    User user = newUser("tok05r4");
    Rotation chain = rotateTwice(user);
    String accessToken = mapper.readTree(chain.latest().body()).path("accessToken").asString("");
    assertFalse(accessToken.isBlank(), "r3 yanitinda accessToken olmali");
    assertEquals(
        200,
        http.get("/api/v1/me", accessToken, null, null).statusCode(),
        "R4 kabul esigi: ayni access token iptal oncesi calismali");

    expireReuseGrace(chain.r1());
    assertEquals(401, refresh(chain.r1()).statusCode());

    assertEquals(
        401,
        http.get("/api/v1/me", accessToken, null, null).statusCode(),
        "R4: aileye ait access token da iptal edilmeli");
  }

  @Test
  @DisplayName("TOK-05 R5 | reuse guvenlik uyarisi ret yanitina ragmen outbox'a commit edilir")
  void tok05_R5_reuseSecurityAlertCommitsDespiteRejection() {
    User user = newUser("tok05r5");
    Rotation chain = rotateTwice(user);
    assertEquals(0L, reuseAlertCount(user), "R5 kabul esigi: normal rotasyonda reuse uyarisi yok");

    expireReuseGrace(chain.r1());
    assertEquals(401, refresh(chain.r1()).statusCode());

    assertTrue(
        reuseAlertCount(user) > 0,
        "R5: ayri baglantidan kullanici, event_type ve reason eslesen outbox kaydi gorunmeli");
  }

  @Test
  @DisplayName("TOK-05 R6 | ayni kullanicinin baska ailesi reuse iptalinden etkilenmez")
  void tok05_R6_otherFamilyOfSameUserRemainsUsable() {
    User user = newUser("tok05r6");
    Rotation chain = rotateTwice(user);
    String otherR1 = authService.login(user.email(), PASSWORD, "127.0.0.1").refreshToken();
    String otherR2 = refreshCookie(refresh(otherR1));
    UUID otherFamilyId = refreshFamily(otherR2);
    assertFalse(refreshFamily(chain.r1()).equals(otherFamilyId), "ikinci login ayri aile acmali");

    expireReuseGrace(chain.r1());
    assertEquals(401, refresh(chain.r1()).statusCode());

    assertAll(
        () -> assertEquals(400, refresh(chain.r3()).statusCode(), "reuse ailesi reddedilmeli"),
        () ->
            assertEquals(
                0L,
                queryLong(
                    "SELECT count(*) FROM refresh_tokens WHERE family_id = ? AND revoked",
                    otherFamilyId),
                "R6: diger ailenin DB satirlari iptal edilmemeli"),
        () ->
            assertEquals(
                200, refresh(otherR2).statusCode(), "R6: diger ailenin refresh'i kabul edilmeli"));
  }

  // =========================================================================================
  // TOK-09 access token'da hassas veri yok
  // =========================================================================================

  @Test
  @DisplayName("TOK-09 | access token payload'i yalniz sub, roles, jti, iat, exp (iss/aud) icerir")
  void tok09_accessTokenPayloadHasNoSensitiveData() throws Exception {
    User user = newUser("tok09");
    String token = authService.login(user.email(), PASSWORD, "127.0.0.1").accessToken();
    String payloadJson = JwtForge.decodePayload(token);
    JsonNode payload = mapper.readTree(payloadJson);

    Set<String> keys = new TreeSet<>();
    payload.propertyNames().forEach(keys::add);
    Set<String> allowed = Set.of("sub", "roles", "jti", "iat", "exp", "iss", "aud");
    assertAll(
        () ->
            assertTrue(
                keys.containsAll(Set.of("sub", "roles", "jti", "iat", "exp")), "eksik: " + keys),
        () -> assertEquals(Set.of(), difference(keys, allowed), "beklenmeyen claim'ler"),
        () -> assertFalse(payloadJson.contains(user.email()), "e-posta payload'da"),
        () -> assertFalse(payloadJson.contains("Sec tok09"), "ad payload'da"),
        () -> assertFalse(payloadJson.toLowerCase().contains("$2a$"), "parola hash'i payload'da"),
        () -> assertEquals(user.id().toString(), payload.path("sub").asString("")));
  }

  // =========================================================================================
  // Yardimcilar
  // =========================================================================================

  private record Rotation(String r1, String r3, HttpResponse<String> latest) {}

  private Rotation rotateTwice(User user) {
    String r1 = authService.login(user.email(), PASSWORD, "127.0.0.1").refreshToken();
    String r2 = refreshCookie(refresh(r1));
    HttpResponse<String> latest = refresh(r2);
    return new Rotation(r1, refreshCookie(latest), latest);
  }

  private static UUID refreshFamily(String rawRefresh) {
    return UUID.fromString(
        queryString(
            "SELECT family_id FROM refresh_tokens WHERE token_hash = ?", sha256(rawRefresh)));
  }

  private static void expireReuseGrace(String rawRefresh) {
    assertEquals(
        1,
        execute(
            "UPDATE refresh_tokens SET used_at = used_at - interval '60 seconds'"
                + " WHERE token_hash = ? AND used_at IS NOT NULL",
            sha256(rawRefresh)));
  }

  private static long reuseAlertCount(User user) {
    return queryLong(
        "SELECT count(*) FROM outbox_events WHERE event_type = 'email.security_alert'"
            + " AND payload ->> 'reason' = 'refresh_token_reuse_detected' AND aggregate_id = ?",
        user.id());
  }

  private User newUser(String tag) {
    String email = "sec-" + tag + "-" + UUID.randomUUID() + "@tracker.local";
    UUID id =
        authService.register(email, PASSWORD, "Sec " + tag, PrivacyNotice.CURRENT_VERSION).getId();
    return new User(id, email);
  }

  private HttpResponse<String> httpLogin(String email, String password) {
    return http.post(
        "/api/v1/auth/login",
        (String) null,
        null,
        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
  }

  private HttpResponse<String> httpRegister(String email) {
    return http.post(
        "/api/v1/auth/register",
        (String) null,
        null,
        "{\"email\":\""
            + email
            + "\",\"password\":\""
            + PASSWORD
            + "\",\"fullName\":\"Sec Reg\",\"privacyNoticeAccepted\":true,"
            + "\"privacyNoticeVersion\":\""
            + PrivacyNotice.CURRENT_VERSION
            + "\"}");
  }

  private String requestResetToken(User user) {
    HttpResponse<String> r =
        http.post(
            "/api/v1/auth/password-reset/request",
            (String) null,
            null,
            "{\"email\":\"" + user.email() + "\"}");
    assertEquals(202, r.statusCode(), r.body());
    return mail.rawToken(user.email(), "sifirlama");
  }

  private HttpResponse<String> confirmReset(String token, String newPassword) {
    return http.post(
        "/api/v1/auth/password-reset/confirm",
        (String) null,
        null,
        "{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
  }

  private HttpResponse<String> verifyEmail(String token) {
    return http.get(
        "/api/v1/auth/verify-email?token="
            + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8),
        (String) null,
        null,
        null);
  }

  private HttpResponse<String> refresh(String rawRefresh) {
    return http.send(
        "POST",
        "/api/v1/auth/refresh",
        null,
        null,
        null,
        Map.of("Cookie", "refresh_token=" + rawRefresh));
  }

  /** Yanittaki Set-Cookie'den yeni refresh token degerini okur. */
  private static String refreshCookie(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
    return response.headers().allValues("Set-Cookie").stream()
        .filter(c -> c.startsWith("refresh_token="))
        .map(c -> c.substring("refresh_token=".length(), c.indexOf(';')))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Set-Cookie refresh_token yok"));
  }

  private void clearThrottle(String email) {
    redis.delete("login_fail:user:" + email + ":last");
  }

  private static boolean is4xx(HttpResponse<?> r) {
    return r.statusCode() >= 400 && r.statusCode() < 500;
  }

  private static long futureEpoch() {
    return System.currentTimeMillis() / 1000 + 600;
  }

  private static String snippet(HttpResponse<String> r) {
    String body = r.body() == null ? "" : r.body();
    return "durum=" + r.statusCode() + " govde=" + body.substring(0, Math.min(200, body.length()));
  }

  private static boolean leaksInternals(String body) {
    if (body == null) {
      return false;
    }
    return body.contains("Exception")
        || body.contains("\tat ")
        || body.contains("stackTrace")
        || body.contains("com.app.tracker")
        || body.contains("java.lang")
        || body.contains("org.springframework")
        || body.contains(".java:");
  }

  private static Set<String> difference(Set<String> a, Set<String> b) {
    Set<String> d = new TreeSet<>(a);
    d.removeAll(b);
    return d;
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

  private static long queryLong(String sql, Object... args) {
    return Long.parseLong(queryString(sql, args));
  }

  /** app_migrator ile duz JDBC: ilk satirin ilk sutunu (boolean pgjdbc'de "t"/"f"). */
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
