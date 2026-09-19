package com.app.tracker.core.outbox;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 2, madde 3 — Message Relay. {@code outbox_events}'teki
 * islenmemis satirlari okuyup Kafka'ya gonderir, gonderimi ({@code .get()} ile senkron beklenerek)
 * dogrulandiktan sonra satiri "islendi" isaretler.
 *
 * <p>Bir batch icinde bir satirin gonderimi patlarsa transaction TAMAMEN geri alinir: bu batch'te
 * daha once basariyla gonderilmis ama henuz commit edilmemis satirlar bir sonraki tick'te TEKRAR
 * gonderilir. Bu kasıtlidir — Outbox Pattern zaten "at-least-once" teslimat + idempotent consumer
 * sozlesmesi ustune kuruludur (bkz. Bolum 3.3, madde 4); mukerrer gonderim veri kaybindan (satirin
 * hic gonderilmemesi) her zaman daha guvenlidir.
 *
 * <p>{@code @Profile("!migrate")}: migration Job'i Kafka'ya baglanmaya hic ihtiyac duymaz (bkz.
 * PHASE_0 Bolum 4.2 — Job SADECE migration calistirir).
 */
@Component
@Profile("!migrate")
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final int BATCH_SIZE = 100;
  private static final long SEND_TIMEOUT_SECONDS = 10;

  private final OutboxEventRepository outboxEventRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  public OutboxRelay(
      OutboxEventRepository outboxEventRepository,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper) {
    this.outboxEventRepository = outboxEventRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
  }

  @Scheduled(fixedDelay = 1000)
  @Transactional
  public void relayBatch() {
    for (OutboxEventRepository.UnprocessedRow row :
        outboxEventRepository.findUnprocessedBatch(BATCH_SIZE)) {
      publish(row);
      outboxEventRepository.markProcessed(row.id());
    }
  }

  private void publish(OutboxEventRepository.UnprocessedRow row) {
    String envelope = buildEnvelope(row);
    try {
      kafkaTemplate
          .send(row.topic(), row.aggregateId().toString(), envelope)
          .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Outbox event gonderimi kesildi: " + row.id(), e);
    } catch (ExecutionException | TimeoutException e) {
      log.warn("Outbox event gonderilemedi, batch geri alinacak: {}", row.id(), e);
      throw new IllegalStateException("Outbox event gonderilemedi: " + row.id(), e);
    }
  }

  private String buildEnvelope(OutboxEventRepository.UnprocessedRow row) {
    JsonNode payload = objectMapper.readTree(row.payloadJson());
    ObjectNode envelope = objectMapper.createObjectNode();
    envelope.put("eventId", row.id().toString());
    envelope.put("eventType", row.eventType());
    envelope.put("schemaVersion", row.schemaVersion());
    envelope.put("timestamp", row.createdAt().toString());
    envelope.put("aggregateId", row.aggregateId().toString());
    if (row.workspaceId() != null) {
      envelope.put("workspaceId", row.workspaceId().toString());
    } else {
      envelope.putNull("workspaceId");
    }
    envelope.set("payload", payload);
    return objectMapper.writeValueAsString(envelope);
  }
}
