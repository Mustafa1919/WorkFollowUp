package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.web.IdempotencyFilter;
import com.app.tracker.security.support.JwtForge;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Actor;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.security.support.SecurityHttp;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dilim 02 sozlesmesi K1-K3 / R1-R11: {@code Idempotency-Key} onbellegi kimlige, kiraciya ve istege
 * baglidir; sir tasiyan yanitlar saklanmaz. Gercek servlet uzerinden HTTP durumlari ve Redis
 * degerleri sinanir; devre disi kalma kosullarinda zincir ve sifir Redis erisimi ayrica dogrulanir.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotencyIsolationTest extends AbstractIntegrationTest {

  private static final String KEY_HEADER = "Idempotency-Key";

  @LocalServerPort private int port;
  @Autowired private ApplicationContext ctx;
  @Autowired private ObjectMapper mapper;
  @Autowired private StringRedisTemplate redis;

  private SecurityFixture fixture;
  private SecurityHttp http;

  @BeforeAll
  void setUp() {
    http = new SecurityHttp(port);
    fixture = SecurityFixture.create(ctx, port);
  }

  // =========================================================================================
  // BL-05 kullanicilar arasi izolasyon
  // =========================================================================================

  @Test
  @DisplayName(
      "BL-05 | baska kiracinin ayni Idempotency-Key + ayni path'i onbellekteki yaniti ALMAZ")
  void R1_bl05_otherTenantSameKeySamePathDoesNotGetCachedBody() {
    Tenant a = fixture.a();
    Tenant b = fixture.b();
    String key = "bl05-" + UUID.randomUUID();
    String nameX = "bl05x" + shortId();
    String nameY = "bl05y" + shortId();

    HttpResponse<String> x = postTag(a, a.admin(), nameX, key);
    assertEquals(201, x.statusCode(), "X'in ilk istegi: " + x.body());
    String xTagId = mapper.readTree(x.body()).path("id").asString("");
    assertFalse(xTagId.isEmpty(), "X yanitinda etiket id'si yok: " + x.body());

    HttpResponse<String> y = postTag(b, b.admin(), nameY, key);
    assertAll(
        () -> assertEquals(201, y.statusCode(), "Y kendi isleminin sonucunu almali: " + y.body()),
        () -> assertFalse(y.body().contains(nameX), "Y, X'in etiket adini aldi: " + y.body()),
        () -> assertFalse(y.body().contains(xTagId), "Y, X'in etiket id'sini aldi: " + y.body()),
        () ->
            assertTrue(y.body().contains(nameY), "Y kendi etiketinin yanitini almadi: " + y.body()),
        () ->
            assertTrue(tagNames(b).contains(nameY), "Y'nin istegi calismadi: etiket B'de olusmadi"),
        () -> assertFalse(tagNames(a).contains(nameY), "Y'nin etiketi A'da gorundu"));
  }

  @Test
  @DisplayName(
      "BL-05 | baska kiracinin ayni Idempotency-Key + FARKLI path'i onbellekteki yaniti ALMAZ")
  void R2_bl05_otherTenantSameKeyDifferentPathDoesNotGetCachedBody() {
    Tenant a = fixture.a();
    Tenant b = fixture.b();
    String key = "bl05-" + UUID.randomUUID();
    String nameX = "bl05x" + shortId();
    String messageY = "bl05 geri bildirim " + shortId();

    HttpResponse<String> x = postTag(a, a.admin(), nameX, key);
    assertEquals(201, x.statusCode(), x.body());
    String xTagId = mapper.readTree(x.body()).path("id").asString("");

    HttpResponse<String> y = postFeedback(b, b.admin(), messageY, key);
    assertAll(
        () -> assertEquals(201, y.statusCode(), "Y geri bildirimi olusturmali: " + y.body()),
        () -> assertFalse(y.body().contains(nameX), "Y, X'in etiket yanitini aldi: " + y.body()),
        () -> assertFalse(y.body().contains(xTagId), "Y, X'in etiket id'sini aldi: " + y.body()),
        () -> assertTrue(y.body().contains(messageY), "Y kendi yanitini almadi: " + y.body()));
  }

  @Test
  @DisplayName(
      "BL-05 | ayni kullanici ayni anahtari FARKLI path'e gonderince ilk yanit kopyalanmaz")
  void R3_bl05_sameUserSameKeyDifferentPathIsNotReplayed() {
    Tenant a = fixture.a();
    String key = "bl05-" + UUID.randomUUID();
    String nameX = "bl05x" + shortId();
    String message = "bl05 geri bildirim " + shortId();

    HttpResponse<String> first = postTag(a, a.admin(), nameX, key);
    assertEquals(201, first.statusCode(), first.body());
    String tagId = mapper.readTree(first.body()).path("id").asString("");

    HttpResponse<String> second = postFeedback(a, a.admin(), message, key);
    assertNotReplay(second, List.of(nameX, tagId), message);
    assertEquals(201, second.statusCode(), second.body());
    assertTrue(second.body().contains(message), second.body());
  }

  @Test
  @DisplayName(
      "BL-05 | ayni kullanici, ayni path, FARKLI govde, ayni anahtar: ilk yanit kopyalanmaz")
  void R4_bl05_sameUserSameKeyDifferentBodyIsNotReplayed() {
    Tenant a = fixture.a();
    String key = "bl05-" + UUID.randomUUID();
    String name1 = "bl05one" + shortId();
    String name2 = "bl05two" + shortId();

    HttpResponse<String> first = postTag(a, a.admin(), name1, key);
    assertEquals(201, first.statusCode(), first.body());
    String tagId = mapper.readTree(first.body()).path("id").asString("");

    HttpResponse<String> second = postTag(a, a.admin(), name2, key);
    assertNotReplay(second, List.of(name1, tagId), name2);
    assertProblem(second, 422, "Ayni Idempotency-Key farkli bir istek govdesiyle kullanildi.");
    assertFalse(tagNames(a).contains(name2), "reddedilen ikinci etiket olusmamali");
  }

  @Test
  @DisplayName("BL-05 | K: ayni kullanici, path, govde, anahtar: ayni yanit, yan etki bir kez")
  void K1_bl05_acceptance_sameRequestReplaysOnceWithSingleSideEffect() {
    Tenant a = fixture.a();
    String key = "bl05-" + UUID.randomUUID();
    String name = "bl05same" + shortId();

    HttpResponse<String> first = postTag(a, a.admin(), name, key);
    HttpResponse<String> second = postTag(a, a.admin(), name, key);

    assertEquals(201, first.statusCode(), first.body());
    assertEquals(201, second.statusCode(), "tekrar ayni yaniti vermeli: " + second.body());
    assertEquals(first.body(), second.body(), "yanit baytlari aynen korunmali");
    assertTrue(
        second.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    assertEquals(
        mapper.readTree(first.body()),
        mapper.readTree(second.body()),
        "tekrar yanitinin JSON govdesi ilkiyle tamamen ayni olmali");
    long count = tagNames(a).stream().filter(name::equals).count();
    assertEquals(1L, count, "yan etki tam bir kez olmali");
  }

  // =========================================================================================
  // BL-06 login/refresh onbellege alinmaz
  // =========================================================================================

  @Test
  @DisplayName(
      "BL-06 | ayni Idempotency-Key ile ikinci kullanici login olunca KENDI token'ini alir")
  void R6_bl06_loginResponseIsNotSharedAcrossUsers() {
    Actor ua = fixture.a().admin();
    Actor ub = fixture.b().admin();
    String key = "bl06-" + UUID.randomUUID();

    HttpResponse<String> la = login(ua.email(), key);
    HttpResponse<String> lb = login(ub.email(), key);
    assertEquals(200, la.statusCode(), la.body());
    assertEquals(200, lb.statusCode(), lb.body());
    String tokenA = accessToken(la);
    String tokenB = accessToken(lb);

    assertAll(
        () -> assertEquals(ua.userId().toString(), subject(tokenA), "A'nin token'i A'ya ait"),
        () ->
            assertEquals(
                ub.userId().toString(),
                subject(tokenB),
                "B ilk kullanicinin onbellekteki token'ini aldi (sub=" + subject(tokenB) + ")"),
        () -> assertNotEquals(tokenA, tokenB, "iki kullanici ayni access token'i aldi"),
        () ->
            assertTrue(
                refreshCookie(lb) != null,
                "B'ye kendi refresh cookie'si verilmedi (onbellekten govde kopyalandi)"));
  }

  @Test
  @DisplayName("BL-06 | login yanitini iceren idempotency anahtari Redis'te yok")
  void R6_bl06_loginResponseIsNotStoredInRedis() {
    Actor ua = fixture.a().manager();
    String key = "bl06-" + UUID.randomUUID();

    HttpResponse<String> la = login(ua.email(), key);
    assertEquals(200, la.statusCode(), la.body());
    String token = accessToken(la);

    List<String> holders = redisKeysHolding(token);
    assertTrue(holders.isEmpty(), "login yaniti idempotency onbellegine yazilmis: " + holders);
  }

  @Test
  @DisplayName("BL-06 | refresh yaniti onbellege alinmaz: ikinci kullanici kendi token'ini alir")
  void R6_bl06_refreshResponseIsNotSharedAcrossUsers() {
    Actor ua = fixture.a().developer();
    Actor ub = fixture.b().developer();
    String refreshA = refreshCookie(login(ua.email(), null));
    String refreshB = refreshCookie(login(ub.email(), null));
    String key = "bl06-" + UUID.randomUUID();

    HttpResponse<String> ra = refresh(refreshA, key);
    HttpResponse<String> rb = refresh(refreshB, key);
    assertEquals(200, ra.statusCode(), ra.body());
    assertEquals(200, rb.statusCode(), rb.body());
    String tokenA = accessToken(ra);
    String tokenB = accessToken(rb);

    assertAll(
        () -> assertEquals(ua.userId().toString(), subject(tokenA)),
        () ->
            assertEquals(
                ub.userId().toString(),
                subject(tokenB),
                "B, A'nin refresh yanitini aldi (sub=" + subject(tokenB) + ")"),
        () ->
            assertTrue(
                redisKeysHolding(tokenA).isEmpty() && redisKeysHolding(tokenB).isEmpty(),
                "refresh yaniti idempotency onbellegine yazilmis: "
                    + redisKeysHolding(tokenA)
                    + redisKeysHolding(tokenB)));
  }

  @Test
  @DisplayName("BL-06 | K: normal POST yaniti onbellege alinir (Redis'te var)")
  void K2_bl06_acceptance_ordinaryPostIsCached() {
    Tenant a = fixture.a();
    String key = "bl06-" + UUID.randomUUID();
    String name = "bl06tag" + shortId();

    HttpResponse<String> r = postTag(a, a.admin(), name, key);
    assertEquals(201, r.statusCode(), r.body());

    List<String> holders = redisKeysHolding(name);
    assertFalse(holders.isEmpty(), "normal POST yaniti idempotency onbellegine alinmadi");
  }

  @Test
  @DisplayName("R1 | ayni workspace icindeki farkli kullanicilarin anahtarlari da ayridir")
  void R1_sameWorkspaceDifferentUsersHaveIndependentKeys() {
    Tenant a = fixture.a();
    String key = "r1-" + UUID.randomUUID();
    String message = "r1 ayni govde " + shortId();
    HttpResponse<String> first = postFeedback(a, a.admin(), message, key);
    HttpResponse<String> second = postFeedback(a, a.developer(), message, key);
    assertEquals(201, first.statusCode(), first.body());
    assertEquals(201, second.statusCode(), second.body());
    String firstId = mapper.readTree(first.body()).path("id").asString("");
    String secondId = mapper.readTree(second.body()).path("id").asString("");
    assertFalse(firstId.isEmpty(), first.body());
    assertFalse(secondId.isEmpty(), second.body());
    assertNotEquals(firstId, secondId, "ikinci kullanicinin islemi ayri calismali");
  }

  @Test
  @DisplayName(
      "R5 | islenen ayni govde istemciye 409 ProblemDetail ulasir; serbest anahtar kabul edilir")
  void R5_inProgressReturns409ToRealHttpClientThenReleasedKeySucceeds() throws Exception {
    Tenant a = fixture.a();
    String key = "r5-" + UUID.randomUUID();
    String name = "r5tag" + shortId();
    String body = tagBody(name);
    String redisKey = contractRedisKey(a, a.admin(), "/api/v1/tags", key);
    String value = "IN_PROGRESS\n" + digest(body);
    redis.opsForValue().set(redisKey, value, Duration.ofHours(24));
    try {
      HttpResponse<String> conflict = postTag(a, a.admin(), name, key);
      assertProblem(conflict, 409, "Ayni Idempotency-Key ile bir istek zaten isleniyor.");
      assertEquals(value, redis.opsForValue().get(redisKey), "diger istegin kilidi korunmali");
      assertFalse(tagNames(a).contains(name), "409 zinciri calistirmamali");
    } finally {
      redis.delete(redisKey);
    }
    HttpResponse<String> accepted = postTag(a, a.admin(), name, key);
    assertEquals(201, accepted.statusCode(), accepted.body());
    HttpResponse<String> replay = postTag(a, a.admin(), name, key);
    assertEquals(201, replay.statusCode(), replay.body());
    assertEquals(accepted.body(), replay.body());
  }

  @Test
  @DisplayName("R7 | ayni anahtarla iki PAT olusur ve duz tokenlar Redis'e yazilmaz")
  void R7_accessTokenCreationBypassesCacheAndNeverStoresSecrets() throws Exception {
    Actor actor = fixture.a().admin();
    String key = "r7-" + UUID.randomUUID();
    String body = "{\"name\":\"r7-" + shortId() + "\"}";
    HttpResponse<String> first =
        http.send(
            "POST",
            "/api/v1/access-tokens",
            actor.accessToken(),
            null,
            body,
            Map.of(KEY_HEADER, key));
    HttpResponse<String> second =
        http.send(
            "POST",
            "/api/v1/access-tokens",
            actor.accessToken(),
            null,
            body,
            Map.of(KEY_HEADER, key));
    assertEquals(201, first.statusCode(), first.body());
    assertEquals(201, second.statusCode(), second.body());
    String token1 = mapper.readTree(first.body()).path("rawToken").asString("");
    String token2 = mapper.readTree(second.body()).path("rawToken").asString("");
    assertFalse(token1.isEmpty(), first.body());
    assertFalse(token2.isEmpty(), second.body());
    assertNotEquals(token1, token2, "ikinci PAT bagimsiz olusmali");
    assertTrue(redisKeysHolding(token1).isEmpty(), "ilk PAT sirri Redis'te");
    assertTrue(redisKeysHolding(token2).isEmpty(), "ikinci PAT sirri Redis'te");
    String scope = actor.userId() + "\n-\nPOST\n/api/v1/access-tokens\n" + key;
    assertFalse(Boolean.TRUE.equals(redis.hasKey("idempotency:" + digest(scope))));
  }

  @Test
  @DisplayName("R8 | 256 karakter anahtar 400 ProblemDetail ve yan etkisiz ret")
  void R8_key256RejectedBeforeDownstream() throws Exception {
    Tenant a = fixture.a();
    String key = "r8-" + UUID.randomUUID() + "x".repeat(217);
    assertEquals(256, key.length());
    String name = "r8reject" + shortId();
    HttpResponse<String> rejected = postTag(a, a.admin(), name, key);
    assertProblem(rejected, 400, "Idempotency-Key en fazla 255 karakter olabilir.");
    assertFalse(tagNames(a).contains(name), "uzun anahtar zincire gecmemeli");
    assertFalse(
        Boolean.TRUE.equals(redis.hasKey(contractRedisKey(a, a.admin(), "/api/v1/tags", key))));
  }

  @Test
  @DisplayName("R8 K1 | 255 karakter anahtar kabul edilir ve aynen tekrar oynatilir")
  void R8_K1_key255AcceptedAndReplayedWithSingleSideEffect() {
    Tenant a = fixture.a();
    String key = "r8-" + UUID.randomUUID() + "x".repeat(216);
    assertEquals(255, key.length());
    String name = "r8accept" + shortId();
    HttpResponse<String> first = postTag(a, a.admin(), name, key);
    HttpResponse<String> replay = postTag(a, a.admin(), name, key);
    assertEquals(201, first.statusCode(), first.body());
    assertEquals(201, replay.statusCode(), replay.body());
    assertEquals(first.body(), replay.body());
    assertEquals(1L, tagNames(a).stream().filter(name::equals).count());
  }

  @Test
  @DisplayName("R9 | Content-Length ile 1 MiB + 1 reddedilir; ayni govde basliksiz kabul edilir")
  void R9_knownLengthOversizeRejectedButSameBodyWithoutKeyAccepted() throws Exception {
    assertOversizeRejectedThenUnkeyedAccepted(false);
  }

  @Test
  @DisplayName("R9 | chunked 1 MiB + 1 okuma sirasinda reddedilir; basliksiz kabul edilir")
  void R9_unknownLengthOversizeRejectedButSameBodyWithoutKeyAccepted() throws Exception {
    assertOversizeRejectedThenUnkeyedAccepted(true);
  }

  @Test
  @DisplayName("R9 K1 | tam 1 MiB govde Jackson'a eksiksiz ulasir ve tekrar oynatilir")
  void R9_K1_exactlyOneMiBAcceptedAndReplayed() throws Exception {
    assertExactBodyLimitAccepted(false);
  }

  @Test
  @DisplayName("R9 K1 | uzunlugu bilinmeyen tam 1 MiB govde kabul edilir ve tekrar oynatilir")
  void R9_K1_unknownLengthExactlyOneMiBAcceptedAndReplayed() throws Exception {
    assertExactBodyLimitAccepted(true);
  }

  @Test
  @DisplayName("R10 | 422 dogrulama reddi anahtari siler; duzeltilmis govde 201 olur")
  void R10_validationFailureReleasesKeyForCorrectedBody() throws Exception {
    Tenant a = fixture.a();
    String key = "r10-" + UUID.randomUUID();
    HttpResponse<String> rejected = postTag(a, a.admin(), "", key);
    assertEquals(422, rejected.statusCode(), rejected.body());
    String redisKey = contractRedisKey(a, a.admin(), "/api/v1/tags", key);
    assertFalse(Boolean.TRUE.equals(redis.hasKey(redisKey)), "basarisiz istegin kilidi kaldi");
    String name = "r10fixed" + shortId();
    HttpResponse<String> corrected = postTag(a, a.admin(), name, key);
    assertEquals(201, corrected.statusCode(), corrected.body());
    assertTrue(corrected.body().contains(name), corrected.body());
    HttpResponse<String> replay = postTag(a, a.admin(), name, key);
    assertEquals(201, replay.statusCode(), replay.body());
    assertEquals(corrected.body(), replay.body());
    assertEquals(1L, tagNames(a).stream().filter(name::equals).count());
  }

  @Test
  @DisplayName(
      "K3 | isAuthenticated true olan anonymous principal Redis'e dokunmadan zincire gecer")
  void K3_anonymousPrincipalBypassesFilterOnNonExcludedPath() throws Exception {
    assertBypassesFilter("application/json", true);
  }

  @Test
  @DisplayName("K3 | Authentication olmayan istek Redis'e dokunmadan zincire gecer")
  void K3_missingAuthenticationBypassesFilterOnNonExcludedPath() throws Exception {
    assertBypassesFilter("application/json", null);
  }

  @Test
  @DisplayName("R11 | kimlikli multipart POST govdeyi tuketmeden ve Redis'e dokunmadan gecer")
  void R11_multipartBypassesFilterWithoutRedisAccess() throws Exception {
    assertBypassesFilter("multipart/form-data; boundary=r11", false);
  }

  @Test
  @DisplayName("R11 | kimlikli form-urlencoded POST Redis'e dokunmadan gecer")
  void R11_urlencodedBypassesFilterWithoutRedisAccess() throws Exception {
    assertBypassesFilter("application/x-www-form-urlencoded; charset=UTF-8", false);
  }

  // =========================================================================================
  // Yardimcilar
  // =========================================================================================

  private void assertBypassesFilter(String contentType, Boolean anonymous) throws Exception {
    StringRedisTemplate untouchedRedis = mock(StringRedisTemplate.class);
    IdempotencyFilter filter = new IdempotencyFilter(untouchedRedis);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tags");
    request.addHeader(KEY_HEADER, "bypass-" + UUID.randomUUID());
    request.setContentType(contentType);
    byte[] originalBody = "name=korunan-govde".getBytes(StandardCharsets.UTF_8);
    request.setContent(originalBody);
    MockHttpServletResponse response = new MockHttpServletResponse();
    SecurityContext previous = SecurityContextHolder.getContext();
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    if (anonymous != null) {
      context.setAuthentication(
          anonymous
              ? new AnonymousAuthenticationToken(
                  "test", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
              : new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, List.of()));
      assertTrue(context.getAuthentication().isAuthenticated());
    }
    SecurityContextHolder.setContext(context);
    List<Boolean> calls = new ArrayList<>();
    try {
      filter.doFilter(
          request,
          response,
          (downstreamRequest, downstreamResponse) -> {
            calls.add(true);
            assertTrue(request == downstreamRequest, "filtre istegi sarmalamamali");
            assertEquals(
                new String(originalBody, StandardCharsets.UTF_8),
                new String(
                    downstreamRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            ((jakarta.servlet.http.HttpServletResponse) downstreamResponse).setStatus(201);
            downstreamResponse.getWriter().write("normal downstream response");
          });
      assertEquals(List.of(true), calls, "zincir tam bir kez calismali");
      assertEquals(201, response.getStatus());
      assertEquals("normal downstream response", response.getContentAsString());
      verifyNoInteractions(untouchedRedis);
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  private void assertOversizeRejectedThenUnkeyedAccepted(boolean chunked) throws Exception {
    Tenant a = fixture.a();
    String name = "r9reject" + shortId();
    String key = "r9-" + UUID.randomUUID();
    String body = paddedTagBody(name, 1024 * 1024 + 1);
    HttpResponse<String> rejected = sendTagBody(a, body, key, chunked);
    assertProblem(
        rejected, 413, "Idempotency-Key kullanilan isteklerde govde en fazla 1 MiB olabilir.");
    assertFalse(tagNames(a).contains(name), "413 zinciri calistirmamali");
    assertFalse(
        Boolean.TRUE.equals(redis.hasKey(contractRedisKey(a, a.admin(), "/api/v1/tags", key))));
    HttpResponse<String> unkeyed = sendTagBody(a, body, null, chunked);
    assertEquals(
        201, unkeyed.statusCode(), "ayni govde basliksiz normal islenmeli: " + unkeyed.body());
    assertTrue(unkeyed.body().contains(name), unkeyed.body());
    assertTrue(redisKeysHolding(name).isEmpty(), "basliksiz yanit onbellege alinmamali");
  }

  private void assertExactBodyLimitAccepted(boolean chunked) throws Exception {
    Tenant a = fixture.a();
    String name = "r9accept" + shortId();
    String key = "r9-" + UUID.randomUUID();
    String body = paddedTagBody(name, 1024 * 1024);
    HttpResponse<String> first = sendTagBody(a, body, key, chunked);
    assertEquals(201, first.statusCode(), first.body());
    assertTrue(first.body().contains(name), "Jackson govdeyi eksiksiz almali: " + first.body());
    HttpResponse<String> replay = sendTagBody(a, body, key, chunked);
    assertEquals(201, replay.statusCode(), replay.body());
    assertEquals(first.body(), replay.body());
    assertEquals(1L, tagNames(a).stream().filter(name::equals).count());
  }

  private HttpResponse<String> sendTagBody(Tenant tenant, String body, String key, boolean chunked)
      throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    HttpRequest.BodyPublisher publisher =
        chunked
            ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
            : HttpRequest.BodyPublishers.ofByteArray(bytes);
    assertEquals(chunked ? -1L : bytes.length, publisher.contentLength());
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(http.url("/api/v1/tags")))
            .version(HttpClient.Version.HTTP_1_1)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + tenant.admin().accessToken())
            .header("X-Workspace-Id", tenant.workspaceId().toString())
            .header("Content-Type", "application/json")
            .POST(publisher);
    if (key != null) {
      request.header(KEY_HEADER, key);
    }
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String tagBody(String name) {
    return "{\"name\":\"" + name + "\",\"color\":\"#AABBCC\"}";
  }

  private static String paddedTagBody(String name, int byteLength) {
    String json = tagBody(name);
    String body = json + " ".repeat(byteLength - json.getBytes(StandardCharsets.UTF_8).length);
    assertEquals(byteLength, body.getBytes(StandardCharsets.UTF_8).length);
    return body;
  }

  private static String digest(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  private static String contractRedisKey(Tenant tenant, Actor actor, String path, String key)
      throws Exception {
    return "idempotency:"
        + digest(actor.userId() + "\n" + tenant.workspaceId() + "\nPOST\n" + path + "\n" + key);
  }

  private void assertProblem(HttpResponse<String> response, int status, String detail) {
    assertEquals(status, response.statusCode(), response.body());
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    assertTrue(contentType.startsWith("application/problem+json"), contentType);
    assertTrue(
        contentType.toLowerCase(java.util.Locale.ROOT).contains("charset=utf-8"), contentType);
    JsonNode json = mapper.readTree(response.body());
    assertEquals(status, json.path("status").asInt());
    assertEquals(detail, json.path("detail").asString());
    assertFalse(json.path("type").asString("").isEmpty(), response.body());
    assertFalse(json.path("title").asString("").isEmpty(), response.body());
  }

  private HttpResponse<String> postTag(Tenant tenant, Actor actor, String name, String key) {
    return http.send(
        "POST",
        "/api/v1/tags",
        actor.accessToken(),
        tenant.workspaceId(),
        "{\"name\":\"" + name + "\",\"color\":\"#AABBCC\"}",
        Map.of(KEY_HEADER, key));
  }

  private HttpResponse<String> postFeedback(
      Tenant tenant, Actor actor, String message, String key) {
    return http.send(
        "POST",
        "/api/v1/feedback",
        actor.accessToken(),
        tenant.workspaceId(),
        "{\"message\":\"" + message + "\",\"pagePath\":\"/bl05\"}",
        Map.of(KEY_HEADER, key));
  }

  private List<String> tagNames(Tenant tenant) {
    HttpResponse<String> r = http.get("/api/v1/tags", tenant.admin(), tenant.workspaceId(), null);
    assertEquals(200, r.statusCode(), r.body());
    List<String> names = new ArrayList<>();
    for (JsonNode tag : mapper.readTree(r.body())) {
      names.add(tag.path("name").asString(""));
    }
    return names;
  }

  /**
   * Sozlesme: "422/409 ya da yeni islem". Kopya DEGILDIR: yanit ilk istegin iceriginden hicbir
   * parca tasimaz VE (409/422 reddi ya da 201 + KENDI isleminin yaniti) olmalidir.
   */
  private static void assertNotReplay(
      HttpResponse<String> second, List<String> firstResponseTokens, String ownMarker) {
    String body = second.body() == null ? "" : second.body();
    int status = second.statusCode();
    assertAll(
        () -> {
          for (String token : firstResponseTokens) {
            assertFalse(
                body.contains(token),
                "ilk yanitin kopyasi dondu (" + token + "): durum=" + status + " govde=" + body);
          }
        },
        () ->
            assertTrue(
                Set.of(409, 422).contains(status) || (status == 201 && body.contains(ownMarker)),
                "beklenen: 409/422 ya da kendi isleminin 201 yaniti; gozlenen durum="
                    + status
                    + " govde="
                    + body));
  }

  private HttpResponse<String> login(String email, String key) {
    Map<String, String> headers = key == null ? Map.of() : Map.of(KEY_HEADER, key);
    return http.send(
        "POST",
        "/api/v1/auth/login",
        null,
        null,
        "{\"email\":\"" + email + "\",\"password\":\"" + SecurityFixture.PASSWORD + "\"}",
        headers);
  }

  private HttpResponse<String> refresh(String refreshToken, String key) {
    return http.send(
        "POST",
        "/api/v1/auth/refresh",
        null,
        null,
        null,
        Map.of("Cookie", "refresh_token=" + refreshToken, KEY_HEADER, key));
  }

  private String accessToken(HttpResponse<String> response) {
    return mapper.readTree(response.body()).path("accessToken").asString("");
  }

  private String subject(String jwt) {
    return mapper.readTree(JwtForge.decodePayload(jwt)).path("sub").asString("");
  }

  private static String refreshCookie(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
    return response.headers().allValues("Set-Cookie").stream()
        .filter(c -> c.startsWith("refresh_token="))
        .map(c -> c.substring("refresh_token=".length(), c.indexOf(';')))
        .findFirst()
        .orElse(null);
  }

  /** {@code idempotency:*} anahtarlarindan degeri {@code needle} iceren olanlar. */
  private List<String> redisKeysHolding(String needle) {
    List<String> holders = new ArrayList<>();
    Set<String> keys = redis.keys("idempotency:*");
    if (keys == null) {
      return holders;
    }
    for (String k : keys) {
      String value = redis.opsForValue().get(k);
      if (value != null && value.contains(needle)) {
        holders.add(k);
      }
    }
    return holders;
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
