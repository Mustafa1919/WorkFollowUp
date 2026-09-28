package com.app.tracker.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dalga 4 -- CycleTimeConsumerIntegrationTest ile AYNI ikili strateji: (1) consumer'in envelope
 * isleme mantigi dogrudan cagrilarak deterministik test edilir (idempotency, ilgisiz/bozuk tip),
 * (2) TEK bir uctan uca test gercek HTTP -> TelemetryController -> Kafka produce ->
 * TelemetryConsumer -> DB zincirinin calistigini dogrular. usage_events/error_events RLS'siz oldugu
 * icin dogrudan JdbcTemplate ile okunur (TenantContext kurmaya gerek yok -- V33 gerekcesi).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TelemetryIntegrationTest extends AbstractIntegrationTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String PASSWORD = "correct-horse-battery";

  @LocalServerPort private int port;
  @Autowired private TelemetryConsumer consumer;
  @Autowired private TelemetryEventPublisher publisher;
  @Autowired private AuthService authService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID workspaceId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
  }

  @Test
  void featureUsedEventIsProjectedToUsageEvents() {
    UUID eventId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-09-28T10:00:00Z");
    consumer.onMessage(
        featureUsedEnvelope(eventId, workspaceId, userId, "board", "viewed", occurredAt));

    var row =
        jdbcTemplate.queryForMap(
            "SELECT workspace_id, user_id, feature, action FROM usage_events WHERE id = ?",
            eventId);
    assertEquals(workspaceId, row.get("workspace_id"));
    assertEquals(userId, row.get("user_id"));
    assertEquals("board", row.get("feature"));
    assertEquals("viewed", row.get("action"));
  }

  @Test
  void errorOccurredEventIsProjectedToErrorEvents() {
    UUID eventId = UUID.randomUUID();
    consumer.onMessage(
        errorOccurredEnvelope(
            eventId,
            workspaceId,
            null,
            "backend",
            "NullPointerException",
            "boom",
            "/api/v1/tasks"));

    var row =
        jdbcTemplate.queryForMap(
            "SELECT source, error_type, message, path FROM error_events WHERE id = ?", eventId);
    assertEquals("backend", row.get("source"));
    assertEquals("NullPointerException", row.get("error_type"));
    assertEquals("boom", row.get("message"));
    assertEquals("/api/v1/tasks", row.get("path"));
  }

  @Test
  void redeliveredEventIsProcessedExactlyOnce() {
    UUID eventId = UUID.randomUUID();
    consumer.onMessage(
        featureUsedEnvelope(eventId, workspaceId, null, "board", "viewed", Instant.now()));
    // Ayni eventId, farkli govde -- ikinci kez islenseydi feature "automation" olurdu.
    consumer.onMessage(
        featureUsedEnvelope(eventId, workspaceId, null, "automation", "clicked", Instant.now()));

    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM usage_events WHERE id = ?", Long.class, eventId);
    assertEquals(1L, count);
    String feature =
        jdbcTemplate.queryForObject(
            "SELECT feature FROM usage_events WHERE id = ?", String.class, eventId);
    assertEquals(
        "board", feature, "ON CONFLICT DO NOTHING: ikinci teslimat ilk satiri degistirmemeli");
  }

  @Test
  void irrelevantEventTypeIsIgnoredAndMalformedIsRejected() {
    consumer.onMessage("{\"eventType\":\"SOMETHING_ELSE\",\"eventId\":\"x\"}");

    assertThrows(IllegalArgumentException.class, () -> consumer.onMessage("not json"));
    assertThrows(
        IllegalArgumentException.class,
        () -> consumer.onMessage("{\"eventType\":\"FEATURE_USED\",\"payload\":{}}"));
  }

  @Test
  void publisherWritesNullWorkspaceWhenWorkspaceIsUnknown() {
    // workspaceId null olabilir (ör. workspace secilmeden once olusan bir frontend hatasi) --
    // publish hicbir istisna firlatmamali, satir workspace_id=NULL ile yazilmali.
    String errorType = "IllegalStateException-" + UUID.randomUUID();
    publisher.publishError(null, null, "backend", errorType, "no workspace", "/x", Instant.now());

    waitUntil(
        () ->
            jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM error_events WHERE error_type = ? AND workspace_id IS NULL",
                    Long.class,
                    errorType)
                >= 1L,
        "workspaceId=NULL hata olayi DB'ye dusmedi");
  }

  @Test
  void realHttpRequestFlowsThroughKafkaIntoUsageAndErrorEvents() throws Exception {
    String email = "telemetry-" + UUID.randomUUID() + "@tracker.local";
    authService.register(email, PASSWORD, "Telemetry User");
    String jwt = authService.login(email, PASSWORD, "127.0.0.1").accessToken();

    HttpResponse<Void> usageResponse =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/telemetry/usage")))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .POST(
                    BodyPublishers.ofString(
                        "{\"events\":[{\"feature\":\"reports\",\"action\":\"viewed\"}]}"))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertEquals(202, usageResponse.statusCode());

    HttpResponse<Void> errorResponse =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/telemetry/errors")))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .POST(
                    BodyPublishers.ofString(
                        "{\"errorType\":\"TypeError\",\"message\":\"x is undefined\",\"path\":\"/reports\"}"))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    assertEquals(202, errorResponse.statusCode());

    waitUntil(
        () ->
            jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM usage_events WHERE feature = 'reports' AND action = 'viewed'",
                    Long.class)
                >= 1L,
        "usage event 30sn icinde Kafka -> consumer -> DB zincirinden gecmedi");
    waitUntil(
        () ->
            jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM error_events WHERE error_type = 'TypeError'", Long.class)
                >= 1L,
        "error event 30sn icinde Kafka -> consumer -> DB zincirinden gecmedi");
  }

  // ---- yardimcilar --------------------------------------------------------------------------

  private String featureUsedEnvelope(
      UUID eventId, UUID workspaceId, UUID userId, String feature, String action, Instant at) {
    return ("{\"eventId\":\"%s\",\"eventType\":\"FEATURE_USED\",\"schemaVersion\":1,"
            + "\"timestamp\":\"%s\",\"workspaceId\":\"%s\","
            + "\"payload\":{\"userId\":%s,\"feature\":\"%s\",\"action\":\"%s\"}}")
        .formatted(
            eventId,
            at,
            workspaceId,
            userId == null ? "null" : "\"" + userId + "\"",
            feature,
            action);
  }

  private String errorOccurredEnvelope(
      UUID eventId,
      UUID workspaceId,
      UUID userId,
      String source,
      String errorType,
      String message,
      String path) {
    return ("{\"eventId\":\"%s\",\"eventType\":\"ERROR_OCCURRED\",\"schemaVersion\":1,"
            + "\"timestamp\":\"%s\",\"workspaceId\":\"%s\","
            + "\"payload\":{\"userId\":%s,\"source\":\"%s\",\"errorType\":\"%s\",\"message\":\"%s\","
            + "\"path\":\"%s\"}}")
        .formatted(
            eventId,
            Instant.now(),
            workspaceId,
            userId == null ? "null" : "\"" + userId + "\"",
            source,
            errorType,
            message,
            path);
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private static void waitUntil(
      java.util.function.BooleanSupplier condition, String failureMessage) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("beklerken kesildi");
      }
    }
    fail(failureMessage);
  }
}
