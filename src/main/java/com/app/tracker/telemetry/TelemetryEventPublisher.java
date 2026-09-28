package com.app.tracker.telemetry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dalga 4 -- urun kullanim + hata telemetrisi. Outbox Pattern'in AKSINE (bkz. OutboxEventRepository
 * javadoc'u), buradaki yazim DOGRUDAN Kafka'ya, tx-disi ve best-effort'tur: bir telemetri olayinin
 * kaybi (broker'a ulasamama) is surecini etkilemez, bu yuzden dual-write garantisinin maliyetini
 * (outbox tablosu + relay) odemeye gerek yok. {@link #publish} hicbir zaman caller'a firlatmaz --
 * biri GlobalExceptionHandler icinden (zaten hata isleniyor), digeri sicak bir controller yolundan
 * (istegi telemetri basarisizligi yuzunden bozmamali) cagrilir.
 */
@Component
public class TelemetryEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(TelemetryEventPublisher.class);
  private static final String TOPIC = "telemetry.events";
  private static final String FEATURE_USED = "FEATURE_USED";
  private static final String ERROR_OCCURRED = "ERROR_OCCURRED";

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  public TelemetryEventPublisher(
      KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
  }

  public void publishFeatureUsed(
      UUID workspaceId, UUID userId, String feature, String action, Instant occurredAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("userId", userId == null ? null : userId.toString());
    payload.put("feature", feature);
    payload.put("action", action);
    publish(FEATURE_USED, workspaceId, occurredAt, payload);
  }

  public void publishError(
      UUID workspaceId,
      UUID userId,
      String source,
      String errorType,
      String message,
      String path,
      Instant occurredAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("userId", userId == null ? null : userId.toString());
    payload.put("source", source);
    payload.put("errorType", errorType);
    payload.put("message", message);
    payload.put("path", path);
    publish(ERROR_OCCURRED, workspaceId, occurredAt, payload);
  }

  private void publish(
      String eventType, UUID workspaceId, Instant occurredAt, Map<String, Object> payload) {
    try {
      UUID eventId = UUID.randomUUID();
      ObjectNode envelope = objectMapper.createObjectNode();
      envelope.put("eventId", eventId.toString());
      envelope.put("eventType", eventType);
      envelope.put("schemaVersion", 1);
      envelope.put("timestamp", occurredAt.toString());
      if (workspaceId != null) {
        envelope.put("workspaceId", workspaceId.toString());
      } else {
        envelope.putNull("workspaceId");
      }
      envelope.set("payload", objectMapper.valueToTree(payload));
      String json = objectMapper.writeValueAsString(envelope);
      kafkaTemplate
          .send(TOPIC, eventId.toString(), json)
          .whenComplete(
              (result, ex) -> {
                if (ex != null) {
                  log.warn(
                      "Telemetri olayi gonderilemedi, yutuluyor (best-effort): {}", eventType, ex);
                }
              });
    } catch (RuntimeException e) {
      log.warn("Telemetri olayi hazirlanamadi, yutuluyor (best-effort): {}", eventType, e);
    }
  }
}
