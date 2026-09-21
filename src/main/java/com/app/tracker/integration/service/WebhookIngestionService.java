package com.app.tracker.integration.service;

import com.app.tracker.integration.WebhookProperties;
import com.app.tracker.integration.repository.WebhookIntegrationLookupRepository.Resolved;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 2 — ingestion: imzayi dogrula, govdeyi PARSE ETMEDEN Kafka'ya
 * birak, hizla 202 don. Is mantigi (gorev bulma/durum guncelleme) burada DEGIL, {@code
 * GithubEventProcessor}'dadir; boylece GitHub'a yanit suresi DB/is mantigi yavasligindan
 * bagimsizdir.
 *
 * <p>Neden outbox degil, dogrudan Kafka: ingestion hicbir is verisi yazmaz (dual-write yoktur),
 * kayit kaybi riski "GitHub 202 almadan once Kafka'ya yazilamadi" durumundadir ve bu 503 ile
 * GitHub'a (yeniden teslim icin) bildirilir. Gonderim ack'i BEKLENIR (senkron {@code get}); ack'siz
 * 202 dondurmek, broker dusunce webhook'un sessizce kaybolmasi demektir.
 *
 * <p>Idempotency burada DEGIL, tuketicide (Processed Event Store) yapilir: burada "gordum" isareti
 * koyup sonra Kafka'ya yazamazsak GitHub'in yeniden teslimi yanlislikla tekrar diye atilirdi.
 * Kafka'ya ayni {@code eventId}'yle iki kez giden mesaj tuketicide zararsizdir.
 */
@Service
@Profile("!migrate")
public class WebhookIngestionService {

  public static final String TOPIC = "webhooks.incoming";
  public static final String EVENT_TYPE = "WEBHOOK_RECEIVED";

  /**
   * Yalniz gorev durumuna etki edebilecek olaylar yayinlanir; gerisi Kafka'yi gereksiz doldurur.
   */
  static final Set<String> SUPPORTED_EVENTS = Set.of("push", "pull_request");

  public enum Outcome {
    ACCEPTED,
    IGNORED,
    PONG,
    UNAUTHORIZED,
    BAD_REQUEST,
    UNAVAILABLE
  }

  private static final Logger log = LoggerFactory.getLogger(WebhookIngestionService.class);

  private final WebhookIntegrationResolver resolver;
  private final WebhookSecretService secretService;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final WebhookProperties properties;

  public WebhookIngestionService(
      WebhookIntegrationResolver resolver,
      WebhookSecretService secretService,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      WebhookProperties properties) {
    this.resolver = resolver;
    this.secretService = secretService;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  public Outcome ingest(
      UUID integrationId,
      String githubEvent,
      String deliveryId,
      String signatureHeader,
      byte[] body) {
    // Bilinmeyen entegrasyon ile yanlis imza AYNI sonucu (401) verir: entegrasyon id'lerinin
    // varligi sizdirilmaz.
    Resolved integration = resolver.resolve(integrationId).orElse(null);
    if (integration == null) {
      return Outcome.UNAUTHORIZED;
    }
    String secret = secretService.deriveSecret(integrationId, integration.secretVersion());
    if (!GithubSignatureVerifier.isValid(secret, body, signatureHeader)) {
      return Outcome.UNAUTHORIZED;
    }

    // Buradan sonrasi IMZALI (guvenilir) istek.
    if (githubEvent == null || githubEvent.isBlank()) {
      return Outcome.BAD_REQUEST;
    }
    if ("ping".equals(githubEvent)) {
      return Outcome.PONG;
    }
    if (!SUPPORTED_EVENTS.contains(githubEvent)) {
      return Outcome.IGNORED;
    }
    UUID delivery = parseDelivery(deliveryId);
    if (delivery == null) {
      return Outcome.BAD_REQUEST;
    }
    return publish(integration, githubEvent, delivery, body);
  }

  private Outcome publish(Resolved integration, String githubEvent, UUID delivery, byte[] body) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("provider", "github");
    payload.put("githubEvent", githubEvent);
    payload.put("deliveryId", delivery.toString());
    payload.put("integrationId", integration.id().toString());
    // Govde PARSE EDILMEZ: ham metin olarak tasinir, yorumlamak tuketicinin isidir.
    payload.put("body", new String(body, StandardCharsets.UTF_8));

    ObjectNode envelope = objectMapper.createObjectNode();
    envelope.put("eventId", eventId(integration.id(), delivery).toString());
    envelope.put("eventType", EVENT_TYPE);
    envelope.put("schemaVersion", 1);
    envelope.put("timestamp", Instant.now().toString());
    envelope.put("aggregateId", integration.id().toString());
    envelope.put("workspaceId", integration.workspaceId().toString());
    envelope.set("payload", payload);

    try {
      kafkaTemplate
          .send(TOPIC, integration.id().toString(), objectMapper.writeValueAsString(envelope))
          .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
      return Outcome.ACCEPTED;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Outcome.UNAVAILABLE;
    } catch (ExecutionException | TimeoutException e) {
      log.warn(
          "Webhook Kafka'ya yazilamadi (integration={}, delivery={})",
          integration.id(),
          delivery,
          e);
      return Outcome.UNAVAILABLE;
    }
  }

  /**
   * Isaretleme anahtari, GitHub'in delivery id'sinden DEGIL {@code (entegrasyon, delivery)}
   * ikilisinden turetilir: imzali bir istek gonderebilen bir tenant, baska bir tenant'in delivery
   * id'sini taklit ederek onun olayini "zaten islendi" saydiramasin (processed_events RLS'sizdir ve
   * global). Ad-tabanli (MD5) UUID guvenlik amacli degil, deterministik esleme icindir.
   */
  static UUID eventId(UUID integrationId, UUID delivery) {
    return UUID.nameUUIDFromBytes(
        (integrationId + ":" + delivery).getBytes(StandardCharsets.UTF_8));
  }

  private static UUID parseDelivery(String deliveryId) {
    if (deliveryId == null) {
      return null;
    }
    try {
      return UUID.fromString(deliveryId);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
