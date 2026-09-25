package com.app.tracker.automation;

import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.task.model.TaskStatus;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code task.events}'i kalici, paylasimli bir consumer group ile ({@link
 * AutomationRuleEngine#CONSUMER}) tuketir — {@code CycleTimeConsumer} ile AYNI desen ({@code
 * earliest}, yapisal hata {@code IllegalArgumentException} ile DLT'ye gider).
 *
 * <p><b>Dongu korumasi:</b> otomasyonun kendi tetikledigi degisiklikler {@link
 * AutomationActor#SYSTEM_USER_ID} adina yazilir (bkz. {@code TaskService} her mutasyonda {@code
 * actorId}'yi payload'a koyar). Bu consumer, {@code actorId} bu sabit kimlige esit olan olaylari
 * ISLEMEZ — boylece bir kuralin tetikledigi olay baska (veya ayni) bir kurali TEKRAR tetikleyemez
 * (derinlik 1 korumasi, ADR-0016). Webhook'un kendi sistem aktoru ({@code IntegrationActor}) FARKLI
 * bir kimlik oldugu icin bu korumadan ETKILENMEZ — bir merge'in tetikledigi Done gecisi,
 * SUBTASK_ALL_DONE_PARENT_TO_REVIEW gibi kurallari normal sekilde tetikleyebilir.
 */
@Component
@Profile("!migrate")
public class AutomationEventConsumer {

  private static final String STATUS_UPDATED = "TASK_STATUS_UPDATED";
  private static final String ASSIGNED = "TASK_ASSIGNED";

  private final ObjectMapper objectMapper;
  private final TenantExecutor tenantExecutor;
  private final AutomationRuleEngine engine;

  public AutomationEventConsumer(
      ObjectMapper objectMapper, TenantExecutor tenantExecutor, AutomationRuleEngine engine) {
    this.objectMapper = objectMapper;
    this.tenantExecutor = tenantExecutor;
    this.engine = engine;
  }

  @KafkaListener(
      topics = "task.events",
      groupId = AutomationRuleEngine.CONSUMER,
      properties = {"auto.offset.reset=earliest"})
  public void onMessage(String envelopeJson) {
    JsonNode envelope = parse(envelopeJson);
    String eventType = envelope.path("eventType").asString(null);
    if (!STATUS_UPDATED.equals(eventType) && !ASSIGNED.equals(eventType)) {
      return;
    }
    JsonNode payload = envelope.path("payload");
    String actorId = payload.path("actorId").asString(null);
    if (AutomationActor.SYSTEM_USER_ID.toString().equals(actorId)) {
      return;
    }
    UUID eventId = UUID.fromString(required(envelope, "eventId"));
    UUID workspaceId = UUID.fromString(required(envelope, "workspaceId"));
    UUID taskId = UUID.fromString(required(payload, "taskId"));

    if (STATUS_UPDATED.equals(eventType)) {
      if (!TaskStatus.DONE.equals(payload.path("newStatus").asString(null))) {
        return;
      }
      tenantExecutor.runAs(workspaceId, () -> engine.onTaskDone(eventId, taskId));
      return;
    }
    String newAssigneeId = payload.path("newAssigneeId").asString(null);
    if (newAssigneeId == null || newAssigneeId.isBlank()) {
      return;
    }
    tenantExecutor.runAs(workspaceId, () -> engine.onTaskAssigned(eventId, taskId));
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
