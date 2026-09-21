package com.app.tracker.integration.consumer;

import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.integration.service.GithubEventProcessor;
import com.app.tracker.integration.service.WebhookIngestionService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 2 — Integration Worker: {@code webhooks.incoming}'i kendi
 * hizinda tuketir. Kalici, PAYLASIMLI consumer group ({@code integration-github}); {@code
 * earliest}: worker ilk kalktiginda topic'te bekleyen webhook'lari da isler.
 *
 * <p>Hata semantigi (bkz. KafkaConsumerConfig): yapisal olarak bozuk mesaj {@link
 * IllegalArgumentException} firlatir (yeniden denemek anlamsiz -> dogrudan DLT); gecici hatalar
 * (DB) ustel geri cekilmeyle yeniden denenir. Baska saglayicinin veya ilgisiz tipteki olaylar
 * sessizce atlanir. Consumer'da JWT yoktur: RLS baglami olayin workspaceId'sinden kurulur (bu
 * workspaceId ingestion'da IMZA dogrulandiktan sonra entegrasyon kaydindan alinir, istekten degil).
 */
@Component
@Profile("!migrate")
public class GithubWebhookConsumer {

  private final ObjectMapper objectMapper;
  private final TenantExecutor tenantExecutor;
  private final GithubEventProcessor processor;

  public GithubWebhookConsumer(
      ObjectMapper objectMapper, TenantExecutor tenantExecutor, GithubEventProcessor processor) {
    this.objectMapper = objectMapper;
    this.tenantExecutor = tenantExecutor;
    this.processor = processor;
  }

  @KafkaListener(
      topics = WebhookIngestionService.TOPIC,
      groupId = GithubEventProcessor.CONSUMER,
      properties = {"auto.offset.reset=earliest"})
  public void onMessage(String envelopeJson) {
    JsonNode envelope = parse(envelopeJson);
    if (!WebhookIngestionService.EVENT_TYPE.equals(envelope.path("eventType").asString(null))) {
      return;
    }
    JsonNode payload = envelope.path("payload");
    if (!"github".equals(payload.path("provider").asString(null))) {
      return;
    }

    UUID eventId = uuid(envelope, "eventId");
    UUID workspaceId = uuid(envelope, "workspaceId");
    String githubEvent = required(payload, "githubEvent");
    String body = required(payload, "body");

    tenantExecutor.runAs(workspaceId, () -> processor.process(eventId, githubEvent, body));
  }

  private JsonNode parse(String envelopeJson) {
    try {
      return objectMapper.readTree(envelopeJson);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("Gecersiz JSON envelope", e);
    }
  }

  private static UUID uuid(JsonNode node, String field) {
    try {
      return UUID.fromString(required(node, field));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Gecersiz UUID alani: " + field, e);
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
