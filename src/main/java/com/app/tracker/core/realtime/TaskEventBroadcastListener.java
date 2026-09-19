package com.app.tracker.core.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 5.1, Secenek A — pod basina benzersiz, rastgele {@code groupId}
 * ile {@code task.events} topic'ini dinler; Kafka bu sayede olayi TUM pod'lara teslim eder
 * (broadcast/pub-sub). Bu consumer, olaylari kalici olarak isleyen (Faz3 Analitik/Notification
 * Worker) paylasimli consumer group'lardan BAGIMSIZDIR — ayni topic, iki farkli tuketim semantigi.
 *
 * <p>Hatalar burada YUTULUR (retry/DLT'ye gitmez): istemci zaten reconnect'te REST'ten guncel
 * state'i ceker (Snapshot-then-Stream, Bolum 5.3), kacan/bozuk bir fan-out mesaji anlamsizdir.
 */
@Component
@Profile("!migrate")
public class TaskEventBroadcastListener {

  private static final Logger log = LoggerFactory.getLogger(TaskEventBroadcastListener.class);

  private final SimpMessagingTemplate messagingTemplate;
  private final ObjectMapper objectMapper;

  public TaskEventBroadcastListener(
      SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
    this.messagingTemplate = messagingTemplate;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(
      topics = "task.events",
      groupId = "ws-fanout-#{T(java.util.UUID).randomUUID().toString()}",
      properties = {"auto.offset.reset=latest", "enable.auto.commit=false"})
  public void fanOutToWebSocket(String envelopeJson) {
    try {
      JsonNode envelope = objectMapper.readTree(envelopeJson);
      JsonNode workspaceIdNode = envelope.get("workspaceId");
      JsonNode projectIdNode = envelope.path("payload").get("projectId");
      if (workspaceIdNode == null || workspaceIdNode.isNull() || projectIdNode == null) {
        log.debug("Fan-out icin workspaceId/projectId eksik, atlaniyor: {}", envelopeJson);
        return;
      }
      String destination =
          "/topic/workspace.%s.project.%s"
              .formatted(workspaceIdNode.asText(), projectIdNode.asText());
      messagingTemplate.convertAndSend(destination, envelopeJson);
    } catch (RuntimeException e) {
      log.debug("Fan-out mesaji islenemedi, atlaniyor.", e);
    }
  }
}
