package com.app.tracker.analytics.consumer;

import com.app.tracker.analytics.service.CycleTimeProjector;
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
 * PHASE_3_DETAILED_DESIGN.md Bolum 1 — {@code task.events}'i kalici, PAYLASIMLI bir consumer group
 * ile ({@code analytics-cycle-time}) tuketir. WebSocket fan-out consumer'indan ({@code
 * TaskEventBroadcastListener}, pod basina rastgele groupId) BAGIMSIZDIR: ayni topic, farkli tuketim
 * semantigi. {@code earliest}: worker ilk kez kalktiginda topic'te kalan gecmisi de isler.
 *
 * <p>Hata semantigi (bkz. KafkaConsumerConfig): yapisal olarak bozuk mesaj {@link
 * IllegalArgumentException} firlatir — yeniden denemek anlamsiz oldugu icin dogrudan DLT'ye gider.
 * Gecici hatalar (DB) ise ustel geri cekilmeyle yeniden denenir. Ilgilenilmeyen olay tipleri
 * sessizce atlanir.
 */
@Component
@Profile("!migrate")
public class CycleTimeConsumer {

  private static final String EVENT_TYPE = "TASK_STATUS_UPDATED";

  /** Silinen gorev Throughput/Cycle Time'dan cikar (read model satiri silinir). */
  private static final String DELETED_EVENT_TYPE = "TASK_DELETED";

  private final ObjectMapper objectMapper;
  private final TenantExecutor tenantExecutor;
  private final CycleTimeProjector projector;

  public CycleTimeConsumer(
      ObjectMapper objectMapper, TenantExecutor tenantExecutor, CycleTimeProjector projector) {
    this.objectMapper = objectMapper;
    this.tenantExecutor = tenantExecutor;
    this.projector = projector;
  }

  @KafkaListener(
      topics = "task.events",
      groupId = CycleTimeProjector.CONSUMER,
      properties = {"auto.offset.reset=earliest"})
  public void onMessage(String envelopeJson) {
    JsonNode envelope = parse(envelopeJson);
    String eventType = envelope.path("eventType").asString(null);
    if (DELETED_EVENT_TYPE.equals(eventType)) {
      UUID eventId = UUID.fromString(required(envelope, "eventId"));
      UUID workspaceId = UUID.fromString(required(envelope, "workspaceId"));
      UUID taskId = UUID.fromString(required(envelope.path("payload"), "taskId"));
      tenantExecutor.runAs(workspaceId, () -> projector.forget(eventId, taskId));
      return;
    }
    if (!EVENT_TYPE.equals(eventType)) {
      return;
    }
    try {
      UUID eventId = UUID.fromString(required(envelope, "eventId"));
      UUID workspaceId = UUID.fromString(required(envelope, "workspaceId"));
      Instant occurredAt = Instant.parse(required(envelope, "timestamp"));
      JsonNode payload = envelope.path("payload");
      UUID taskId = UUID.fromString(required(payload, "taskId"));
      UUID projectId = UUID.fromString(required(payload, "projectId"));
      String newStatus = required(payload, "newStatus");

      // Consumer HTTP istegi disinda calisir, JWT yoktur: RLS baglamini olayin kendi
      // workspaceId'sinden kurariz.
      tenantExecutor.runAs(
          workspaceId,
          () -> projector.project(eventId, taskId, workspaceId, projectId, newStatus, occurredAt));
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
}
