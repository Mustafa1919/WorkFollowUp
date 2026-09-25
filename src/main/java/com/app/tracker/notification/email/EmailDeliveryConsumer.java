package com.app.tracker.notification.email;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code notification.email} topic'i, PAYLASIMLI grup {@code notification-email}. {@code
 * auto.offset.reset=latest} BILEREK: bu consumer ilk kez devreye giriyor, topic'te BUGUNE KADAR
 * biriken (hicbir zaman tuketilmemis) tum dogrulama/sifirlama olaylarini bir anda gondermek hem
 * anlamsiz (cogu token suresi dolmus) hem de SMTP saglayicisina karsi ani bir yuk olurdu —
 * ADR-0010.
 *
 * <p>Bu topic'in tek uretim yolu ({@code EmailNotificationPublisher}) DB transaction'i icinde
 * outbox'a yazar; consumer HTTP istegi disinda calisir, tenant baglami GEREKMEZ (auth tablolari
 * RLS'e tabi degil).
 */
@Component
@Profile("!migrate")
public class EmailDeliveryConsumer {

  private final ObjectMapper objectMapper;
  private final EmailDeliveryService service;

  public EmailDeliveryConsumer(ObjectMapper objectMapper, EmailDeliveryService service) {
    this.objectMapper = objectMapper;
    this.service = service;
  }

  @KafkaListener(
      topics = "notification.email",
      groupId = EmailDeliveryService.CONSUMER,
      properties = {"auto.offset.reset=latest"})
  public void onMessage(String envelopeJson) {
    JsonNode envelope = parse(envelopeJson);
    UUID eventId = uuid(envelope, "eventId");
    String eventType = envelope.path("eventType").asString(null);
    JsonNode payload = envelope.path("payload");
    service.handle(eventId, eventType, payload);
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
