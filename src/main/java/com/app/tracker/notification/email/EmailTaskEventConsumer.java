package com.app.tracker.notification.email;

import com.app.tracker.core.tenancy.TenantExecutor;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code task.events}, PAYLASIMLI grup {@code notification-email-task}, Slack/Inbox ile AYNI
 * gerekceyle {@code auto.offset.reset=latest}: gecmis atama/mention'lar e-postaya bosaltilmaz.
 *
 * <p>{@code COMMENT_MENTION} {@code CommentService} tarafindan uretilir (ADR-0009); payload {@code
 * taskId}, {@code projectId}, {@code actorId}, {@code commentId}, {@code mentionedUserIds} (UUID
 * string dizisi) tasir.
 *
 * <p>Gorev/proje ozeti RLS'li tablolardan okundugu icin tenant baglami olayin {@code
 * workspaceId}'si ile kurulur ({@code SlackNotificationConsumer} ile ayni desen); kurulmazsa sorgu
 * HATA degil sessizce bos doner ve e-posta hic gitmeden olay islendi sayilirdi.
 */
@Component
@Profile("!migrate")
public class EmailTaskEventConsumer {

  private static final String TASK_ASSIGNED = "TASK_ASSIGNED";
  private static final String COMMENT_MENTION = "COMMENT_MENTION";

  private final ObjectMapper objectMapper;
  private final TenantExecutor tenantExecutor;
  private final EmailTaskEventService service;

  public EmailTaskEventConsumer(
      ObjectMapper objectMapper, TenantExecutor tenantExecutor, EmailTaskEventService service) {
    this.objectMapper = objectMapper;
    this.tenantExecutor = tenantExecutor;
    this.service = service;
  }

  @KafkaListener(
      topics = "task.events",
      groupId = EmailTaskEventService.CONSUMER,
      properties = {"auto.offset.reset=latest"})
  public void onMessage(String envelopeJson) {
    JsonNode envelope = parse(envelopeJson);
    String eventType = envelope.path("eventType").asString(null);
    if (!TASK_ASSIGNED.equals(eventType) && !COMMENT_MENTION.equals(eventType)) {
      return;
    }
    UUID eventId = uuid(envelope, "eventId");
    UUID workspaceId = uuid(envelope, "workspaceId");
    JsonNode payload = envelope.path("payload");
    tenantExecutor.runAs(
        workspaceId,
        () -> {
          if (TASK_ASSIGNED.equals(eventType)) {
            service.handleTaskAssigned(eventId, workspaceId, payload);
          } else {
            service.handleCommentMention(eventId, workspaceId, payload);
          }
        });
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
