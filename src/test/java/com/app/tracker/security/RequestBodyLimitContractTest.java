package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.security.support.SecurityFixture;
import com.app.tracker.security.support.SecurityFixture.Tenant;
import com.app.tracker.security.support.SecurityHttp;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.NestedTestConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dilim 02: beklentiler sozlesmenin R1-R6 / K1-K6 maddelerinden gelir. Tum istekler gercek
 * servlet'e gider. Dolgu JSON'dan ONCEDIR: Jackson gecersiz/eksik govdeye ulasmadan once butun
 * dolgu baytlarini okumak zorundadir. Uretim filtresi veya sabitleri test duzeneginde kullanilmaz.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestBodyLimitContractTest extends AbstractIntegrationTest {

  private static final int LIMIT = 2_097_152;
  private static final String TELEMETRY = "/api/v1/telemetry/errors";
  private static final String LOGIN = "/api/v1/auth/login";
  private static final String TAGS = "/api/v1/tags";
  private static final String SMALL_WEBHOOK = "{\"ref\":\"refs/heads/main\",\"commits\":[]}";
  private static final String SMALL_ERROR =
      "{\"errorType\":\"BodyLimitContract\",\"message\":\"sinir ölçümü\",\"path\":\"/contract\"}";
  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  @LocalServerPort private int port;
  @Autowired private ApplicationContext context;
  private Harness h;

  @BeforeAll
  void setUp() {
    h = new Harness(context, port);
  }

  @Test
  void R1_contentLengthRejectedBeforeAnyBodyByteIsSent() throws Exception {
    String headers =
        "POST "
            + TELEMETRY
            + " HTTP/1.1\r\n"
            + "Host: localhost\r\nContent-Type: application/json\r\n"
            + "Authorization: Bearer "
            + h.tenant.admin().accessToken()
            + "\r\n"
            + "X-Workspace-Id: "
            + h.tenant.workspaceId()
            + "\r\n"
            + "Content-Length: "
            + (LIMIT + 1)
            + "\r\nConnection: close\r\n\r\n";
    // Govde hic gonderilmez; Jackson/denetleyici okumaya kalkarsa soket zaman asimina ugrar.
    assertEquals(413, headersOnlyStatus(port, headers));
  }

  @Test
  void K1_smallAuthenticatedTelemetryStillReturns202() throws Exception {
    assertEquals(202, h.telemetry(SMALL_ERROR, false).statusCode());
  }

  @Test
  void R1_R6_knownLengthLimitPlusOneReturnsSafeProblem() throws Exception {
    assertGeneralProblem(h.telemetry(padded(SMALL_ERROR, LIMIT + 1), false), LIMIT, h.mapper);
  }

  @Test
  void R2_R6_oversizedAnonymousLoginReturns413() throws Exception {
    assertGeneralProblem(
        h.send(LOGIN, padded(h.loginBody(SecurityFixture.PASSWORD), LIMIT + 1), false, false, null),
        LIMIT,
        h.mapper);
  }

  @Test
  void K2_normalAnonymousLoginWithCorrectPasswordReturns200() throws Exception {
    HttpResponse<String> response =
        h.send(LOGIN, h.loginBody(SecurityFixture.PASSWORD), false, false, null);
    assertEquals(200, response.statusCode(), response.body());
    assertFalse(h.mapper.readTree(response.body()).path("accessToken").asString("").isBlank());
  }

  @Test
  void K2_normalAnonymousLoginWithWrongPasswordKeepsBusinessRuleResponse() throws Exception {
    // Projede yanlis parola BusinessRuleException -> 400 (AuthService.login); boyut filtresi bunu
    // degistirmemeli.
    HttpResponse<String> response =
        h.send(LOGIN, h.loginBody("incorrect-contract-password"), false, false, null);
    assertEquals(400, response.statusCode(), response.body());
  }

  @Test
  void R2_R6_largeAnonymousProtectedRequestIsLimitedBeforeAuthentication() throws Exception {
    assertGeneralProblem(
        h.send(TELEMETRY, padded(SMALL_ERROR, LIMIT + 1), false, false, null), LIMIT, h.mapper);
  }

  @Test
  void K2_smallAnonymousProtectedRequestStillRequiresAuthentication() throws Exception {
    HttpResponse<String> response = h.send(TELEMETRY, SMALL_ERROR, false, false, null);
    assertEquals(401, response.statusCode(), response.body());
  }

  @Test
  void R3_R6_chunkedLimitPlusOneReturnsSafe413InsteadOf400Or500() throws Exception {
    assertGeneralProblem(h.telemetry(padded(SMALL_ERROR, LIMIT + 1), true), LIMIT, h.mapper);
  }

  @Test
  void K3_chunkedUnkeyedBodyBetweenOneAndTwoMiBIsAccepted() throws Exception {
    HttpResponse<String> response = h.telemetry(padded(SMALL_ERROR, 1_572_864), true);
    assertEquals(202, response.statusCode(), response.body());
  }

  @Test
  void K3_chunkedRequestIsNotReadAheadOfAuthentication() throws Exception {
    String headers =
        "POST "
            + TELEMETRY
            + " HTTP/1.1\r\n"
            + "Host: localhost\r\nContent-Type: application/json\r\n"
            + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
    // Henuz tek chunk yokken guvenlik yanit verebilmeli; on-okuma bloklanir.
    assertEquals(401, headersOnlyStatus(port, headers));
  }

  @Test
  void R4_K3_chunkedKeyedOversizeLeavesNoRedisRecordAndSameKeyCanSucceed() throws Exception {
    String key = "body-contract-" + UUID.randomUUID();
    String name = "contract" + UUID.randomUUID().toString().replace("-", "");
    String body = tagBody(name);
    String redisKey = h.redisKey(TAGS, key);
    assertFalse(Boolean.TRUE.equals(h.redis.hasKey(redisKey)));
    HttpResponse<String> rejected = h.send(TAGS, padded(body, LIMIT + 1), true, true, key);
    assertProblemEnvelope(rejected, h.mapper);
    // Duzeltme paragrafi: varsayilan ayarda anahtarli govdeyi once 1 MiB siniri kesebilir.
    assertFalse(Boolean.TRUE.equals(h.redis.hasKey(redisKey)), "kayit/kilit kaldi");
    assertEquals(0, h.tagCount(name), "reddedilen istek denetleyiciye ulasmamali");
    h.assertKeyReusable(key, body, name);
  }

  @Test
  void K4_exactTwoMiBWithContentLengthIsAccepted() throws Exception {
    HttpResponse<String> response = h.telemetry(padded(SMALL_ERROR, LIMIT), false);
    assertEquals(202, response.statusCode(), response.body());
  }

  @Test
  void K4_exactTwoMiBChunkedIsAccepted() throws Exception {
    HttpResponse<String> response = h.telemetry(padded(SMALL_ERROR, LIMIT), true);
    assertEquals(202, response.statusCode(), response.body());
  }

  @Test
  void R5_webhookKeeps512KiBLimitWithContentLength() throws Exception {
    h.assertWebhookLimit(false);
  }

  @Test
  void R5_webhookKeeps512KiBLimitWithChunkedBody() throws Exception {
    h.assertWebhookLimit(true);
  }

  @Test
  void K5_smallSignedWebhookStillReturns202ForKnownLengthAndChunked() throws Exception {
    for (boolean chunked : new boolean[] {false, true}) {
      HttpResponse<String> response = h.webhook(SMALL_WEBHOOK, chunked);
      assertEquals(202, response.statusCode(), response.body());
    }
  }

  @Test
  void R6_K1_smallUnreadableJsonRemains400AndValidJsonIsAccepted() throws Exception {
    for (boolean chunked : new boolean[] {false, true}) {
      HttpResponse<String> malformed = h.telemetry("{\"errorType\":", chunked);
      assertEquals(400, malformed.statusCode(), malformed.body());
      assertEquals(202, h.telemetry(SMALL_ERROR, chunked).statusCode());
    }
  }

  /** K6 ayari varsayilandan bagimsiz bir Spring context'inde sinanir; secicide dis sinif yeter. */
  @Nested
  @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = "app.http.max-request-body-size=2KB")
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class ConfiguredLimit extends AbstractIntegrationTest {

    @LocalServerPort private int configuredPort;
    @Autowired private ApplicationContext configuredContext;
    private Harness configured;

    @BeforeAll
    void setUpConfigured() {
      configured = new Harness(configuredContext, configuredPort);
    }

    @Test
    void K6_R1_K4_knownLengthUses2048ByteLimitAndAcceptsExactBoundary() throws Exception {
      assertGeneralProblem(
          configured.telemetry(padded(SMALL_ERROR, 2049), false), 2048, configured.mapper);
      HttpResponse<String> accepted = configured.telemetry(padded(SMALL_ERROR, 2048), false);
      assertEquals(202, accepted.statusCode(), accepted.body());
    }

    @Test
    void K6_R3_K3_chunkedUses2048ByteLimitAndAcceptsExactBoundary() throws Exception {
      assertGeneralProblem(
          configured.telemetry(padded(SMALL_ERROR, 2049), true), 2048, configured.mapper);
      HttpResponse<String> accepted = configured.telemetry(padded(SMALL_ERROR, 2048), true);
      assertEquals(202, accepted.statusCode(), accepted.body());
    }

    @Test
    void K6_R4_chunkedKeyedOverflowStays413AndSameKeyCanBeReused() throws Exception {
      String key = "configured-contract-" + UUID.randomUUID();
      // TagRequest.name en fazla 40 karakter.
      String name = "configured" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
      String body = tagBody(name);
      String redisKey = configured.redisKey(TAGS, key);
      assertFalse(Boolean.TRUE.equals(configured.redis.hasKey(redisKey)));
      HttpResponse<String> rejected = configured.send(TAGS, padded(body, 2049), true, true, key);
      assertAll(
          () -> assertProblemEnvelope(rejected, configured.mapper),
          () -> assertFalse(Boolean.TRUE.equals(configured.redis.hasKey(redisKey))),
          () -> assertEquals(0, configured.tagCount(name)),
          () -> configured.assertKeyReusable(key, body, name));
    }

    @Test
    void K6_R6_chunkedKeyedGlobalOverflowReportsActualLimit() throws Exception {
      String key = "configured-detail-" + UUID.randomUUID();
      HttpResponse<String> rejected =
          configured.send(TAGS, padded(tagBody("detail"), 2049), true, true, key);
      assertAll(
          () -> assertGeneralProblem(rejected, 2048, configured.mapper),
          () -> assertEquals(202, configured.telemetry(SMALL_ERROR, true).statusCode()));
    }

    @Test
    void K6_R5_R6_K5_chunkedWebhookRawReaderMapsGlobalOverflowAndAcceptsSmallBody()
        throws Exception {
      HttpResponse<String> rejected = configured.webhook(padded(SMALL_WEBHOOK, 2049), true);
      assertGeneralProblem(rejected, 2048, configured.mapper);
      HttpResponse<String> accepted = configured.webhook(SMALL_WEBHOOK, true);
      assertEquals(202, accepted.statusCode(), accepted.body());
    }
  }

  private static int headersOnlyStatus(int port, String headers) throws Exception {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("localhost", port), 10_000);
      socket.setSoTimeout(10_000);
      socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      String statusLine = reader.readLine();
      assertNotNull(statusLine, "govde gonderilmeden HTTP yaniti bekleniyor");
      assertTrue(statusLine.startsWith("HTTP/1.1 "), statusLine);
      return Integer.parseInt(statusLine.split(" ")[1]);
    }
  }

  private static String padded(String json, int totalBytes) {
    int jsonBytes = json.getBytes(StandardCharsets.UTF_8).length;
    assertTrue(totalBytes >= jsonBytes);
    String body = " ".repeat(totalBytes - jsonBytes) + json;
    assertEquals(totalBytes, body.getBytes(StandardCharsets.UTF_8).length);
    return body;
  }

  private static String tagBody(String name) {
    return "{\"name\":\"" + name + "\",\"color\":\"#AABBCC\"}";
  }

  private static JsonNode assertProblemEnvelope(
      HttpResponse<String> response, ObjectMapper mapper) {
    assertEquals(413, response.statusCode(), response.body());
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    assertTrue(contentType.startsWith("application/problem+json"), contentType);
    assertFalse(response.body().isBlank(), "413 govdesi bos olamaz");
    JsonNode problem = mapper.readTree(response.body());
    assertEquals("https://api.app.com/errors/payload-too-large", problem.path("type").asString());
    assertEquals(413, problem.path("status").asInt());
    assertFalse(problem.path("title").asString("").isBlank());
    assertFalse(problem.path("detail").asString("").isBlank());
    for (String forbidden :
        new String[] {
          "Exception",
          "com.app.",
          "org.springframework",
          "java.lang",
          "\tat ",
          "Istek govdesi siniri asildi"
        }) {
      assertFalse(response.body().contains(forbidden), "ic bilgi sizintisi: " + response.body());
    }
    for (String field : new String[] {"trace", "stackTrace", "exception", "stack"}) {
      assertFalse(problem.has(field), response.body());
    }
    return problem;
  }

  private static void assertGeneralProblem(
      HttpResponse<String> response, long limit, ObjectMapper mapper) {
    JsonNode problem = assertProblemEnvelope(response, mapper);
    assertAll(
        () -> assertEquals("Istek Govdesi Cok Buyuk", problem.path("title").asString()),
        () ->
            assertTrue(
                problem.path("detail").asString("").contains(Long.toString(limit)),
                "detail siniri bayt olarak bildirmeli: " + response.body()));
  }

  private static final class Harness {

    private final int serverPort;
    private final SecurityHttp http;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final Tenant tenant;
    private String webhookPath;
    private String webhookSecret;

    private Harness(ApplicationContext ctx, int port) {
      serverPort = port;
      http = new SecurityHttp(port);
      mapper = ctx.getBean(ObjectMapper.class);
      redis = ctx.getBean(StringRedisTemplate.class);
      tenant = SecurityFixture.create(ctx, port).a();
    }

    private String loginBody(String password) {
      return mapper.writeValueAsString(
          Map.of("email", tenant.admin().email(), "password", password));
    }

    private HttpResponse<String> telemetry(String body, boolean chunked) throws Exception {
      return send(TELEMETRY, body, chunked, true, null);
    }

    private HttpResponse<String> send(
        String path, String body, boolean chunked, boolean authenticated, String key)
        throws Exception {
      return send(path, body, chunked, authenticated, key, Map.of());
    }

    private HttpResponse<String> send(
        String path,
        String body,
        boolean chunked,
        boolean authenticated,
        String key,
        Map<String, String> extraHeaders)
        throws Exception {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      HttpRequest.BodyPublisher publisher =
          chunked
              ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
              : HttpRequest.BodyPublishers.ofByteArray(bytes);
      assertEquals(chunked ? -1L : bytes.length, publisher.contentLength());
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path))
              .version(HttpClient.Version.HTTP_1_1)
              .timeout(Duration.ofSeconds(60))
              .header("Content-Type", "application/json")
              .POST(publisher);
      if (authenticated) {
        request.header("Authorization", "Bearer " + tenant.admin().accessToken());
        request.header("X-Workspace-Id", tenant.workspaceId().toString());
      }
      if (key != null) {
        request.header("Idempotency-Key", key);
      }
      extraHeaders.forEach(request::header);
      HttpResponse<String> response =
          CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (chunked) {
        assertFalse(response.request().headers().firstValue("Content-Length").isPresent());
      }
      return response;
    }

    private String redisKey(String path, String key) throws Exception {
      String scope =
          tenant.admin().userId() + "\n" + tenant.workspaceId() + "\nPOST\n" + path + "\n" + key;
      return "idempotency:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(scope.getBytes(StandardCharsets.UTF_8)));
    }

    private int tagCount(String name) {
      HttpResponse<String> response = http.get(TAGS, tenant.admin(), tenant.workspaceId(), null);
      assertEquals(200, response.statusCode(), response.body());
      int count = 0;
      for (JsonNode tag : mapper.readTree(response.body())) {
        if (name.equals(tag.path("name").asString(""))) {
          count++;
        }
      }
      return count;
    }

    private void assertKeyReusable(String key, String body, String name) throws Exception {
      HttpResponse<String> accepted = send(TAGS, body, true, true, key);
      assertEquals(201, accepted.statusCode(), accepted.body());
      assertTrue(accepted.body().contains(name), accepted.body());
      assertTrue(Boolean.TRUE.equals(redis.hasKey(redisKey(TAGS, key))), "kabul kaydi yok");
      HttpResponse<String> replay = send(TAGS, body, true, true, key);
      assertEquals(201, replay.statusCode(), replay.body());
      assertEquals(accepted.body(), replay.body());
      assertEquals(1, tagCount(name), "tekrar yan etki olusturmamali");
    }

    private void createWebhook() {
      if (webhookPath != null) {
        return;
      }
      HttpResponse<String> response =
          http.post("/api/v1/integrations/webhooks", tenant.admin(), tenant.workspaceId(), null);
      assertEquals(201, response.statusCode(), response.body());
      JsonNode json = mapper.readTree(response.body());
      webhookPath = json.path("integration").path("webhookPath").asString("");
      webhookSecret = json.path("secret").asString("");
      assertTrue(webhookPath.startsWith("/api/v1/webhooks/github/"), response.body());
      assertFalse(webhookSecret.isBlank(), response.body());
    }

    private HttpResponse<String> webhook(String body, boolean chunked) throws Exception {
      createWebhook();
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String signature =
          "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
      return send(
          webhookPath,
          body,
          chunked,
          false,
          null,
          Map.of(
              "X-Hub-Signature-256",
              signature,
              "X-GitHub-Event",
              "push",
              "X-GitHub-Delivery",
              UUID.randomUUID().toString()));
    }

    private void assertWebhookLimit(boolean chunked) throws Exception {
      // Her iki aralik da genel 2 MiB sinirinin altinda, webhook sinirinin ustundedir.
      for (int bytes : new int[] {524_289, 1_572_864}) {
        HttpResponse<String> response = webhook(padded(SMALL_WEBHOOK, bytes), chunked);
        assertEquals(413, response.statusCode(), "webhook bayt=" + bytes + ": " + response.body());
      }
    }
  }
}
