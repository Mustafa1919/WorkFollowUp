package com.app.tracker.telemetry;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code telemetry.events} -- CycleTimeConsumer ile AYNI zarf/hata semantigi (yapisal olarak bozuk
 * mesaj IllegalArgumentException ile dogrudan DLT'ye gider, KafkaConsumerConfig'teki tek global
 * DefaultErrorHandler tum listener'lara otomatik uygulanir). RLS baglami KURULMAZ -- usage_events/
 * error_events RLS'siz altyapi tablolaridir (V33).
 */
@Component
@Profile("!migrate")
public class TelemetryConsumer {

  private static final String CONSUMER_GROUP = "telemetry-projector";
  private static final String FEATURE_USED = "FEATURE_USED";
  private static final String ERROR_OCCURRED = "ERROR_OCCURRED";

  private final ObjectMapper objectMapper;
  private final TelemetryEventRepository repository;

  public TelemetryConsumer(ObjectMapper objectMapper, TelemetryEventRepository repository) {
    this.objectMapper = objectMapper;
    this.repository = repository;
  }

  @KafkaListener(
      topics = "telemetry.events",
      groupId = CONSUMER_GROUP,
      properties = {"auto.offset.reset=earliest"})
  public void onMessage(String envelopeJson) {
    JsonNode envelope = parse(envelopeJson);
    String eventType = envelope.path("eventType").asString(null);
    if (!FEATURE_USED.equals(eventType) && !ERROR_OCCURRED.equals(eventType)) {
      return;
    }
    try {
      UUID eventId = UUID.fromString(required(envelope, "eventId"));
      Instant occurredAt = Instant.parse(required(envelope, "timestamp"));
      UUID workspaceId = optionalUuid(envelope, "workspaceId");
      JsonNode payload = envelope.path("payload");
      UUID userId = optionalUuid(payload, "userId");

      if (FEATURE_USED.equals(eventType)) {
        repository.insertUsageEvent(
            eventId,
            workspaceId,
            userId,
            required(payload, "feature"),
            required(payload, "action"),
            occurredAt);
      } else {
        repository.insertErrorEvent(
            eventId,
            workspaceId,
            userId,
            required(payload, "source"),
            required(payload, "errorType"),
            payload.path("message").asString(null),
            payload.path("path").asString(null),
            occurredAt);
      }
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Gecersiz timestamp: " + envelopeJson, e);
    }
  }

  private JsonNode parse(String envelopeJson) {
    try {
      return objectMapper.readTree(envelopeJson);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("Gecersiz JSON envelope", e);
    }
  }

  private static String required(JsonNode node, String field) {
    String value = node.path(field).asString(null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Eksik alan: " + field);
    }
    return value;
  }

  private static UUID optionalUuid(JsonNode node, String field) {
    String value = node.path(field).asString(null);
    return value == null || value.isBlank() ? null : UUID.fromString(value);
  }
}
