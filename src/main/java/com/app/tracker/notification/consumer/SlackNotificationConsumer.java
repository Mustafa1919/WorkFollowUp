package com.app.tracker.notification.consumer;

import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.notification.service.SlackMessageFormatter;
import com.app.tracker.notification.service.SlackNotificationService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 3 — Notification Worker: {@code task.events}'i kalici,
 * PAYLASIMLI bir consumer group ({@code notification-slack}) ile tuketir; Cycle Time worker'indan
 * ve WebSocket fan-out'undan BAGIMSIZDIR (ayni topic, ayri tuketim).
 *
 * <p>{@code auto.offset.reset=latest} BILEREK (diger worker'larin {@code earliest}'inin tersi):
 * analitik gecmisi yeniden kurar, ama bildirim GECMISI Slack'e boca etmek istenmez. Yeni group (ilk
 * deploy, group id degisimi) yalniz bundan sonraki olaylari bildirir.
 *
 * <p>Hata semantigi (bkz. KafkaConsumerConfig): bozuk mesaj {@link IllegalArgumentException}
 * (yeniden denemesiz DLT); {@code SlackTransientException} ustel geri cekilmeyle yeniden denenir,
 * sonra DLT. Kalici Slack reddi serviste yutulur (DLT'yi olu adreslerle doldurmamak icin).
 */
@Component
@Profile("!migrate")
public class SlackNotificationConsumer {

  private final ObjectMapper objectMapper;
  private final TenantExecutor tenantExecutor;
  private final SlackNotificationService service;

  public SlackNotificationConsumer(
      ObjectMapper objectMapper, TenantExecutor tenantExecutor, SlackNotificationService service) {
    this.objectMapper = objectMapper;
    this.tenantExecutor = tenantExecutor;
    this.service = service;
  }

  @KafkaListener(
      topics = "task.events",
      groupId = SlackNotificationService.CONSUMER,
      properties = {"auto.offset.reset=latest"})
  public void onMessage(String envelopeJson) {
    JsonNode envelope = parse(envelopeJson);
    String eventType = envelope.path("eventType").asString(null);
    if (!SlackMessageFormatter.isNotifiable(eventType)) {
      return;
    }
    UUID eventId = uuid(envelope, "eventId");
    UUID workspaceId = uuid(envelope, "workspaceId");
    JsonNode payload = envelope.path("payload");

    // Consumer HTTP istegi disinda calisir, JWT yoktur: RLS baglamini olayin kendi
    // workspaceId'sinden kurariz.
    tenantExecutor.runAs(
        workspaceId, () -> service.handle(workspaceId, eventId, eventType, payload));
  }

  private JsonNode parse(String envelopeJson) {
    try {
      return objectMapper.readTree(envelopeJson);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("Gecersiz JSON envelope", e);
    }
  }

  private static UUID uuid(JsonNode node, String field) {
    String value = node.path(field).asString(null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Eksik alan: " + field);
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Gecersiz UUID alani: " + field, e);
    }
  }
}
