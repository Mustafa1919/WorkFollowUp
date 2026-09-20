package com.app.tracker.analytics.consumer;

import com.app.tracker.analytics.service.VelocityProjector;
import com.app.tracker.core.tenancy.TenantExecutor;
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
 * PHASE_3_DETAILED_DESIGN.md Bolum 1.1, kural 1 — {@code sprint.events}'ten gelen {@code
 * SPRINT_COMPLETED} olayi Velocity/Spillover hesaplamasini tetikler. Hata semantigi ve consumer
 * group deseni {@link CycleTimeConsumer} ile aynidir.
 *
 * <p>Hesaplamanin kesit zamani envelope {@code timestamp}'i (outbox satirinin tx baslangici) DEGIL,
 * payload'daki {@code completedAt}'tir: SprintService bu degeri sprint'in kapandigi anin degismez
 * kaydi olarak yazar.
 */
@Component
@Profile("!migrate")
public class VelocityConsumer {

  private static final String EVENT_TYPE = "SPRINT_COMPLETED";

  private final ObjectMapper objectMapper;
  private final TenantExecutor tenantExecutor;
  private final VelocityProjector projector;

  public VelocityConsumer(
      ObjectMapper objectMapper, TenantExecutor tenantExecutor, VelocityProjector projector) {
    this.objectMapper = objectMapper;
    this.tenantExecutor = tenantExecutor;
    this.projector = projector;
  }

  @KafkaListener(
      topics = "sprint.events",
      groupId = VelocityProjector.CONSUMER,
      properties = {"auto.offset.reset=earliest"})
  public void onMessage(String envelopeJson) {
    JsonNode envelope = parse(envelopeJson);
    if (!EVENT_TYPE.equals(envelope.path("eventType").asString(null))) {
      return;
    }
    try {
      UUID eventId = UUID.fromString(required(envelope, "eventId"));
      UUID workspaceId = UUID.fromString(required(envelope, "workspaceId"));
      JsonNode payload = envelope.path("payload");
      UUID sprintId = UUID.fromString(required(payload, "sprintId"));
      UUID projectId = UUID.fromString(required(payload, "projectId"));
      String name = required(payload, "name");
      Instant completedAt = Instant.parse(required(payload, "completedAt"));

      tenantExecutor.runAs(
          workspaceId,
          () -> projector.project(eventId, sprintId, workspaceId, projectId, name, completedAt));
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Gecersiz completedAt: " + envelopeJson, e);
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
}
