package com.app.tracker.core.notification;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.4.3 — e-posta gonderimi senkron yapilmaz, bir {@code
 * notification.email} Kafka olayi olarak uretilir; gercek gonderim Faz3 Notification Worker'in isi.
 *
 * <p>Bilinen sinirlama: bu, Mimari.md Bolum 6'daki Transactional Outbox Pattern'i DEGIL, dogrudan
 * {@code KafkaTemplate.send} cagrisidir — DB yazimi ile Kafka publish'i ayni transaction'da degil
 * (dual-write riski var). Outbox altyapisi Faz2'nin kapsami; bu servis o altyapi kuruldugunda relay
 * worker'a devredilecek sekilde degistirilecek.
 */
@Component
public class EmailNotificationPublisher {

  private static final String TOPIC = "notification.email";

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  public EmailNotificationPublisher(
      KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
  }

  public void publishVerificationEmail(UUID userId, String purpose, String rawToken) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("userId", userId.toString());
    payload.put("token", rawToken);
    publish("email." + purpose.toLowerCase(java.util.Locale.ROOT), userId, payload);
  }

  public void publishSecurityAlert(UUID userId, String reason) {
    publish("email.security_alert", userId, Map.of("userId", userId.toString(), "reason", reason));
  }

  private void publish(String eventType, UUID aggregateId, Map<String, Object> payload) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", UUID.randomUUID().toString());
    envelope.put("eventType", eventType);
    envelope.put("schemaVersion", 1);
    envelope.put("timestamp", Instant.now().toString());
    envelope.put("aggregateId", aggregateId.toString());
    envelope.put("workspaceId", null);
    envelope.put("payload", payload);
    kafkaTemplate.send(TOPIC, aggregateId.toString(), objectMapper.writeValueAsString(envelope));
  }
}
