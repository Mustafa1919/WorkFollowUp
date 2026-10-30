package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.GlobalExceptionHandler;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.telemetry.TelemetryEventPublisher;
import com.app.tracker.telemetry.TelemetryRedactor;
import com.app.tracker.user.model.PrivacyNotice;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.NestedTestConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dilim 03: beklentiler sozlesmeden gelir; yer tutucular uretim sabitlerinden alinmaz. Docker'siz
 * secici: TelemetryRedactionContractTest#unit_*. Tam sinif HTTP/Kafka/DB'yi de kosar. R11/K11
 * davranisi ek Node betiginde ve frontend build/lint komutlariyla sinanir.
 */
class TelemetryRedactionContractTest {

  private static final String EMAIL = "ad.soyad+etiket@alt.example.com";
  private static final String PAT = "wf_pat_Az09_-FULL_body-123";
  private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnXy0";
  private static final String UNSIGNED = "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.";
  private static final String PLAIN = "Cannot read properties of undefined (reading 'map')";
  private static final String PROJECT =
      "/api/v1/projects/3f2c0000-0000-4000-8000-000000000001/tasks";
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final ClassPathResource V37 =
      new ClassPathResource("db/migration/V37__purge_unredacted_error_events.sql");
  private final TelemetryRedactor redactor = new TelemetryRedactor();

  @Test
  void unit_R1_K1_emailAndOrdinaryMessage() {
    assertEquals("user [email] failed", redactor.redactMessage("user " + EMAIL + " failed"));
    assertEquals("[email], [email]", redactor.redactMessage("a@b.com, c@d.org"));
    assertEquals(PLAIN, redactor.redactMessage(PLAIN));
  }

  @Test
  void unit_R2_K1_patConsumesEntireBodyAndPreservesOrdinaryText() {
    assertEquals("401 [pat] end", redactor.redactMessage("401 " + PAT + " end"));
    assertEquals("wf_path body-123", redactor.redactMessage("wf_path body-123"));
  }

  @Test
  void unit_R3_K1_signedAndUnsignedJwtAndOrdinaryDottedText() {
    assertEquals("[jwt] [jwt]", redactor.redactMessage(JWT + " " + UNSIGNED));
    assertEquals("version.1.2", redactor.redactMessage("version.1.2"));
  }

  @Test
  void unit_R4_K1_bearerOpaquePatAndJwtAreFullyReplaced() {
    for (String value : List.of("opaque-value_1", PAT, JWT)) {
      assertEquals(
          "auth Bearer [redacted] denied",
          redactor.redactMessage("auth bEaReR " + value + " denied"));
    }
    assertEquals("Bearer", redactor.redactMessage("Bearer"));
    assertEquals(PLAIN, redactor.redactMessage(PLAIN));
  }

  @Test
  void unit_R4_R6b_K1_bearerValueWithColonDoesNotLeakSuffix() {
    assertAll(
        () ->
            assertEquals(
                "auth Bearer [redacted] denied",
                redactor.redactMessage("auth Bearer opaque:private-value denied")),
        () ->
            assertEquals(
                "/auth/Bearer [redacted]",
                redactor.redactPath("/auth/Bearer opaque:private-value")));
    assertEquals("opaque:public-value", redactor.redactMessage("opaque:public-value"));
    assertEquals("/auth/opaque:public-value", redactor.redactPath("/auth/opaque:public-value"));
  }

  @Test
  void unit_R5_K5_allSecretQueryNamesAndPublicParameters() {
    for (String name :
        List.of("token", "CoDe", "KEY", "secret", "Password", "ACCESS_TOKEN", "refresh_token")) {
      assertEquals(
          "GET /x?page=2&" + name + "=[redacted]&size=50#anchor",
          redactor.redactMessage("GET /x?page=2&" + name + "=private-value&size=50#anchor"));
    }
    assertEquals(
        "/x?token=[redacted]&token=[redacted]&code=[redacted]",
        redactor.redactMessage("/x?token=one&token=two&code="));
    assertEquals("/x?page=2&size=50", redactor.redactMessage("/x?page=2&size=50"));
    assertEquals("/x?token_hint=public", redactor.redactMessage("/x?token_hint=public"));
  }

  @Test
  void unit_R6_K6_invitationQueryHashAndUuidPaths() {
    for (String prefix : List.of("/invitations/", "/api/v1/invitations/")) {
      assertEquals(prefix + "{token}", redactor.redactPath(prefix + "private?code=secret#hash"));
    }
    assertEquals(
        "/api/v1/invitations/{token}/accept",
        redactor.redactPath("/api/v1/invitations/private/accept?token=secret#hash"));
    assertEquals("/reports", redactor.redactPath("/reports#hash?code=secret"));
    assertEquals("/reports", redactor.redactPath("/reports?code=secret#hash"));
    assertEquals(PROJECT, redactor.redactPath(PROJECT));
    assertEquals("/reports", redactor.redactPath("/reports"));
  }

  @Test
  void unit_R6b_K6_identifiersAndBearerInUntrustedPath() {
    assertEquals("/u/[email]", redactor.redactPath("/u/" + EMAIL));
    assertEquals("/p/[pat]", redactor.redactPath("/p/" + PAT));
    assertEquals("/j/[jwt]", redactor.redactPath("/j/" + JWT));
    assertEquals("/j/[jwt]", redactor.redactPath("/j/" + UNSIGNED));
    assertEquals("/auth/Bearer [redacted]", redactor.redactPath("/auth/bEaReR opaque-value"));
    assertEquals(PROJECT, redactor.redactPath(PROJECT));
  }

  @Test
  void unit_R7_K7_errorTypeOnlyRedactsIdentifiers() {
    assertEquals(
        "E [email] [pat] [jwt] [jwt]",
        redactor.redactErrorType("E " + EMAIL + " " + PAT + " " + JWT + " " + UNSIGNED));
    String marker = "log05-email-" + UUID.randomUUID();
    assertEquals(marker, redactor.redactErrorType(marker));
  }

  @Test
  void unit_K7_errorTypePreservesBearerQueryAndInvitation() {
    String type = "Bearer opaque-value /invitations/private?token=keep#anchor";
    assertEquals(type, redactor.redactErrorType(type));
    assertEquals("x".repeat(190), redactor.redactErrorType("x".repeat(190)));
  }

  @Test
  void unit_K8_nullAndEmptyFieldsAreSentWithoutDroppingEvent() {
    Capture c = new Capture(redactor);
    c.send("TypeError", null, null);
    JsonNode payload = c.payload();
    assertTrue(payload.get("message").isNull());
    assertTrue(payload.get("path").isNull());
    assertEquals("TypeError", payload.get("errorType").asString());
    assertNull(redactor.redactMessage(null));
    assertNull(redactor.redactPath(null));
    Capture empty = new Capture(redactor);
    empty.send("TypeError", "", "");
    assertEquals("", empty.payload().get("message").asString());
    assertEquals("", empty.payload().get("path").asString());
  }

  @Test
  void unit_R9_K8_redactionFailureIsClosedForEachFieldAndStillPublishes() {
    for (String field : List.of("message", "path", "errorType")) {
      for (boolean stackOverflow : List.of(false, true)) {
        TelemetryRedactor broken = spy(new TelemetryRedactor());
        Throwable failure =
            stackOverflow
                ? new StackOverflowError("contract")
                : new IllegalStateException("contract");
        switch (field) {
          case "message" -> doThrow(failure).when(broken).redactMessage(anyString());
          case "path" -> doThrow(failure).when(broken).redactPath(anyString());
          case "errorType" -> doThrow(failure).when(broken).redactErrorType(anyString());
          default -> fail(field);
        }
        Capture c = new Capture(broken);
        assertDoesNotThrow(() -> c.send("E " + EMAIL, "mail " + EMAIL, "/u/" + EMAIL));
        JsonNode p = c.payload();
        // R9 sabit mesaj yer tutucusunu tanimlar; diger alanlarda ham veri sizmamali.
        if (field.equals("message")) {
          assertEquals("[redaction-failed]", p.get("message").asString());
        }
        assertFalse(p.get("message").isNull());
        assertFalse(c.json().contains(EMAIL));
      }
    }
    Capture healthy = new Capture(redactor);
    healthy.send("E", PLAIN, PROJECT);
    assertEquals(PLAIN, healthy.payload().get("message").asString());
  }

  @Test
  void unit_R1_R2_R3_R6b_K8_backend500HandlerPublishesMaskedJson() {
    Capture c = new Capture(redactor);
    GlobalExceptionHandler handler = new GlobalExceptionHandler(c.publisher);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/u/" + EMAIL);
    assertEquals(
        500,
        handler
            .handleAllUncaughtException(
                new IllegalStateException("failed " + EMAIL + " " + PAT + " " + JWT), request)
            .getStatus());
    JsonNode p = c.payload();
    assertEquals("backend", p.get("source").asString());
    assertEquals("IllegalStateException", p.get("errorType").asString());
    assertEquals("failed [email] [pat] [jwt]", p.get("message").asString());
    assertEquals("/u/[email]", p.get("path").asString());
    for (String raw : List.of(EMAIL, PAT, JWT)) {
      assertFalse(c.json().contains(raw));
    }
    Capture healthy = new Capture(redactor);
    new GlobalExceptionHandler(healthy.publisher)
        .handleAllUncaughtException(new IllegalStateException(PLAIN), request);
    assertEquals(PLAIN, healthy.payload().get("message").asString());
  }

  @Test
  void unit_R9_K8_brokerFailureNeverEscapesPublisher() {
    Capture c = new Capture(redactor);
    when(c.kafka.send(anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("broker unavailable"));
    assertDoesNotThrow(() -> c.send("E", PLAIN, PROJECT));
    verify(c.kafka).send(eq("telemetry.events"), anyString(), anyString());
    Capture healthy = new Capture(redactor);
    healthy.send("E", PLAIN, PROJECT);
    assertEquals(PLAIN, healthy.payload().get("message").asString());
  }

  @Test
  void unit_K8_messageIsClippedTo4000BeforeRedaction() {
    String prefix = "x".repeat(3990);
    assertEquals(prefix + "[pat]", redactor.redactMessage(prefix + "wf_pat_ABCDEF" + EMAIL));
    assertEquals("x".repeat(4000), redactor.redactMessage("x".repeat(4000)));
    assertEquals("x".repeat(4000), redactor.redactMessage("x".repeat(4001)));
    Capture c = new Capture(redactor);
    c.send("E", "x".repeat(8000), PROJECT);
    assertEquals("x".repeat(4000), c.payload().get("message").asString());
  }

  @Test
  void unit_R1_R3_R4_K1_ReDoSInputsFinishWithin500msEach() {
    redactor.redactMessage(PLAIN);
    for (String attack :
        List.of(
            "a@".repeat(5000),
            "eyJ" + "a".repeat(12000),
            "Bearer ".repeat(1500),
            "a.".repeat(6000),
            "a".repeat(12000))) {
      assertTimeoutPreemptively(
          Duration.ofMillis(500), () -> assertNotNull(redactor.redactMessage(attack)));
    }
    assertEquals(PLAIN, redactor.redactMessage(PLAIN));
  }

  @Test
  void unit_R10_K10_migrationOnlyDeletesErrorEventsAndIsRepeatableSql() throws Exception {
    String sql =
        V37.getContentAsString(StandardCharsets.UTF_8).replaceAll("(?m)--[^\\r\\n]*", "").trim();
    assertEquals("DELETE FROM error_events;", sql);
  }

  private static final class Capture {
    private final KafkaTemplate<String, String> kafka;
    private final TelemetryEventPublisher publisher;
    private final ArgumentCaptor<String> captured = ArgumentCaptor.forClass(String.class);

    @SuppressWarnings("unchecked")
    Capture(TelemetryRedactor redactor) {
      kafka = mock(KafkaTemplate.class);
      when(kafka.send(anyString(), anyString(), anyString()))
          .thenReturn(CompletableFuture.completedFuture(null));
      publisher = new TelemetryEventPublisher(kafka, JSON, redactor);
    }

    void send(String type, String message, String path) {
      publisher.publishError(null, null, "backend", type, message, path, Instant.now());
    }

    String json() {
      verify(kafka).send(eq("telemetry.events"), anyString(), captured.capture());
      return captured.getValue();
    }

    JsonNode payload() {
      JsonNode envelope = JSON.readTree(json());
      assertEquals("ERROR_OCCURRED", envelope.get("eventType").asString());
      assertNotNull(UUID.fromString(envelope.get("eventId").asString()));
      return envelope.get("payload");
    }
  }

  /** Tek gercek Spring context; mock publisher/consumer yok, her vaka DB satirini zorunlu tutar. */
  @Nested
  @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  class HttpKafka extends AbstractIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private AuthService authService;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String accessToken;

    // Her test kendi kullanicisiyla: telemetry-error kullanici basina hiz siniri (SEC-07,
    // 30/dk) sinif boyu tek kullanicinin butcesini tuketmesin.
    @BeforeEach
    void authenticate() {
      String email = "redaction-contract-" + UUID.randomUUID() + "@tracker.local";
      authService.register(
          email, "correct-horse-battery", "Contract User", PrivacyNotice.CURRENT_VERSION);
      accessToken = authService.login(email, "correct-horse-battery", "127.0.0.1").accessToken();
    }

    @Test
    void R1_K1_K8_emailHasAnExistingExactlyMaskedRow() throws Exception {
      stored("mail " + EMAIL + " failed", "/reports", "mail [email] failed", "/reports");
      stored(PLAIN, "/reports", PLAIN, "/reports");
    }

    @Test
    void R2_K1_K8_entirePatBodyIsMasked() throws Exception {
      stored("401 " + PAT + " end", "/reports", "401 [pat] end", "/reports");
      stored("wf_path body-123", "/reports", "wf_path body-123", "/reports");
    }

    @Test
    void R3_K1_K8_signedAndUnsignedJwtAreMasked() throws Exception {
      stored(JWT + " " + UNSIGNED, "/reports", "[jwt] [jwt]", "/reports");
      stored("version.1.2", "/reports", "version.1.2", "/reports");
    }

    @Test
    void R4_K1_K8_caseInsensitiveBearerCoversOpaquePatAndJwt() throws Exception {
      for (String value : List.of("opaque-value_1", PAT, JWT)) {
        stored("bEaReR " + value, "/reports", "Bearer [redacted]", "/reports");
      }
      stored("Bearer", "/reports", "Bearer", "/reports");
    }

    @Test
    void R4_R6b_K1_K8_bearerColonValueIsCompletelyMasked() throws Exception {
      stored("Bearer opaque:private-value", "/reports", "Bearer [redacted]", "/reports");
      stored(PLAIN, "/auth/Bearer opaque:private-value", PLAIN, "/auth/Bearer [redacted]");
      stored("opaque:public-value", "/reports", "opaque:public-value", "/reports");
    }

    @Test
    void R5_K5_K8_allSecretQueryNamesKeepOtherParameters() throws Exception {
      for (String name :
          List.of("token", "CoDe", "KEY", "secret", "Password", "ACCESS_TOKEN", "refresh_token")) {
        stored(
            "GET /x?page=2&" + name + "=private&size=50",
            "/reports",
            "GET /x?page=2&" + name + "=[redacted]&size=50",
            "/reports");
      }
      stored("GET /x?page=2&size=50", "/reports", "GET /x?page=2&size=50", "/reports");
    }

    @Test
    void R6_K6_K8_invitationPathsAndQueryHashAreCleaned() throws Exception {
      stored(PLAIN, "/invitations/private?token=secret#hash", PLAIN, "/invitations/{token}");
      stored(PLAIN, "/api/v1/invitations/private", PLAIN, "/api/v1/invitations/{token}");
      stored(
          PLAIN,
          "/api/v1/invitations/private/accept?code=secret#hash",
          PLAIN,
          "/api/v1/invitations/{token}/accept");
      stored(PLAIN, "/reports#hash?token=secret", PLAIN, "/reports");
      stored(PLAIN, "/reports?token=secret#hash", PLAIN, "/reports");
      stored(PLAIN, PROJECT, PLAIN, PROJECT);
    }

    @Test
    void R6b_R1_R2_R3_R4_K6_K8_pathCannotBypassIdentifierRules() throws Exception {
      stored(PLAIN, "/u/" + EMAIL, PLAIN, "/u/[email]");
      stored(PLAIN, "/p/" + PAT, PLAIN, "/p/[pat]");
      stored(PLAIN, "/j/" + JWT, PLAIN, "/j/[jwt]");
      stored(PLAIN, "/j/" + UNSIGNED, PLAIN, "/j/[jwt]");
      stored(PLAIN, "/auth/bEaReR opaque-value", PLAIN, "/auth/Bearer [redacted]");
      stored(PLAIN, PROJECT, PLAIN, PROJECT);
    }

    @Test
    void R7_K7_K8_errorTypeIsRedactedWithoutLosingTheRow() throws Exception {
      for (String[] pair :
          List.of(
              new String[] {EMAIL, "[email]"},
              new String[] {PAT, "[pat]"},
              new String[] {JWT, "[jwt]"},
              new String[] {UNSIGNED, "[jwt]"})) {
        String marker = "contract-" + UUID.randomUUID() + " ";
        postAndAwait(marker + pair[0], PLAIN, PROJECT, marker + pair[1], PLAIN, PROJECT);
      }
      String marker = "log05-email-" + UUID.randomUUID();
      postAndAwait(marker, PLAIN, PROJECT, marker, PLAIN, PROJECT);
    }

    @Test
    void K7_K8_errorTypeRetainsBearerQueryAndInvitation() throws Exception {
      String type =
          "contract-" + UUID.randomUUID() + " Bearer opaque /invitations/private?token=keep#hash";
      postAndAwait(type, PLAIN, PROJECT, type, PLAIN, PROJECT);
    }

    @Test
    void K8_nullAndEmptyMessagesAndPathsStillHaveRows() throws Exception {
      stored(null, null, null, null);
      stored("", "", "", "");
      stored(PLAIN, PROJECT, PLAIN, PROJECT);
    }

    @Test
    void R10_K10_V37PurgesErrorsTwiceAndPreservesUsageInsideRollback() throws Exception {
      // Paylasilan tablolari kalici temizlememek icin gercek SQL tek baglantida geri alinir.
      try (Connection connection = openMigratorConnection()) {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
          UUID usage = UUID.randomUUID();
          UUID error = UUID.randomUUID();
          statement.executeUpdate(
              "INSERT INTO usage_events(id,feature,action,occurred_at) VALUES ('"
                  + usage
                  + "','contract','viewed',now())");
          statement.executeUpdate(
              "INSERT INTO error_events(id,source,error_type,message,occurred_at) VALUES ('"
                  + error
                  + "','backend','contract','"
                  + EMAIL
                  + "',now())");
          try (ResultSet rows =
              statement.executeQuery(
                  "SELECT count(*) FROM error_events WHERE id='" + error + "'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
          }
          for (int run = 0; run < 2; run++) {
            ScriptUtils.executeSqlScript(connection, V37);
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM error_events")) {
              assertTrue(rows.next());
              assertEquals(0, rows.getLong(1));
            }
            try (ResultSet rows =
                statement.executeQuery(
                    "SELECT feature,action FROM usage_events WHERE id='" + usage + "'")) {
              assertTrue(rows.next(), "V37 usage satirini sildi");
              assertEquals("contract", rows.getString(1));
              assertEquals("viewed", rows.getString(2));
            }
          }
        } finally {
          connection.rollback();
        }
      }
    }

    private void stored(String message, String path, String expectedMessage, String expectedPath)
        throws Exception {
      String marker = "contract-" + UUID.randomUUID();
      postAndAwait(marker, message, path, marker, expectedMessage, expectedPath);
    }

    private void postAndAwait(
        String type,
        String message,
        String path,
        String expectedType,
        String expectedMessage,
        String expectedPath)
        throws Exception {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("errorType", type);
      body.put("message", message);
      body.put("path", path);
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(
                      URI.create("http://localhost:" + port + "/api/v1/telemetry/errors"))
                  .timeout(Duration.ofSeconds(30))
                  .header("Authorization", "Bearer " + accessToken)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(202, response.statusCode(), response.body());
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      List<Map<String, Object>> rows;
      do {
        rows =
            jdbc.queryForList(
                "SELECT source,error_type,message,path FROM error_events WHERE error_type = ?",
                expectedType);
        if (!rows.isEmpty()) {
          assertEquals(1, rows.size(), "Olay tam bir kez yazilmali");
          Map<String, Object> row = rows.getFirst();
          assertEquals("frontend", row.get("source"));
          assertEquals(expectedType, row.get("error_type"));
          assertEquals(expectedMessage, row.get("message"));
          assertEquals(expectedPath, row.get("path"));
          return;
        }
        Thread.sleep(100);
      } while (System.nanoTime() < deadline);
      fail("K8: HTTP 202 sonrasi Kafka/consumer/DB satiri yok: " + expectedType);
    }
  }
}
