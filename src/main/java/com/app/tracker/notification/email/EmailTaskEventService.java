package com.app.tracker.notification.email;

import com.app.tracker.notification.preferences.service.NotificationPreferencesService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * {@code task.events} uzerinden gorev-kaynakli e-postalar: {@code TASK_ASSIGNED} (atanana) ve
 * {@code COMMENT_MENTION} (etiketlenen her kullaniciya, coklu alici). Bu ikisi KULLANICI
 * TERCIHINDEN gecer ({@code NotificationPreferencesService}) — auth/guvenlik e-postalarinin ({@code
 * EmailDeliveryService}) aksine, kapatilabilir.
 *
 * <p>Coklu alicili tek bir olay (COMMENT_MENTION) icin idempotency anahtari {@code eventId} TEK
 * BASINA yetmez (ayni olay birden fazla kisiye e-posta uretir); {@code WebhookIngestionService} ile
 * AYNI teknikle alici basina turetilmis bir kimlik kullanilir: {@code
 * UUID.nameUUIDFromBytes(eventId + ":" + recipientId)}.
 */
@Service
@Profile("!migrate")
public class EmailTaskEventService {

  public static final String CONSUMER = "notification-email-task";

  private static final Logger log = LoggerFactory.getLogger(EmailTaskEventService.class);

  private final EmailDeliveryStore store;
  private final NotificationPreferencesService preferencesService;
  private final EmailSender sender;
  private final EmailProperties properties;

  public EmailTaskEventService(
      EmailDeliveryStore store,
      NotificationPreferencesService preferencesService,
      EmailSender sender,
      EmailProperties properties) {
    this.store = store;
    this.preferencesService = preferencesService;
    this.sender = sender;
    this.properties = properties;
  }

  public void handleTaskAssigned(UUID eventId, UUID workspaceId, JsonNode payload) {
    UUID actorId = uuidOrNull(payload, "actorId");
    UUID newAssigneeId = uuidOrNull(payload, "newAssigneeId");
    if (newAssigneeId == null || newAssigneeId.equals(actorId)) {
      return;
    }
    UUID taskId = uuid(payload, "taskId");
    deliverIfEligible(
        eventId,
        workspaceId,
        newAssigneeId,
        taskId,
        preferencesService::emailOnAssign,
        EmailTemplates::taskAssignedEmail);
  }

  public void handleCommentMention(UUID eventId, UUID workspaceId, JsonNode payload) {
    UUID actorId = uuidOrNull(payload, "actorId");
    UUID taskId = uuid(payload, "taskId");
    for (UUID mentionedId : mentionedUserIds(payload)) {
      if (mentionedId.equals(actorId)) {
        continue;
      }
      deliverIfEligible(
          eventId,
          workspaceId,
          mentionedId,
          taskId,
          preferencesService::emailOnMention,
          EmailTemplates::mentionEmail);
    }
  }

  private interface Preference {
    boolean allows(UUID userId);
  }

  private interface Template {
    EmailContent build(
        String publicUrl,
        UUID projectId,
        UUID taskId,
        String projectKey,
        int taskNumber,
        String title);
  }

  private void deliverIfEligible(
      UUID eventId,
      UUID workspaceId,
      UUID recipientId,
      UUID taskId,
      Preference preference,
      Template template) {
    UUID dedupeKey = perRecipientEventId(eventId, recipientId);
    if (store.alreadyProcessed(CONSUMER, dedupeKey)) {
      return;
    }
    if (!store.isWorkspaceMember(workspaceId, recipientId)) {
      store.markProcessed(CONSUMER, dedupeKey);
      return;
    }
    if (!preference.allows(recipientId)) {
      store.markProcessed(CONSUMER, dedupeKey);
      return;
    }
    Optional<String> email = store.findUserEmail(recipientId);
    Optional<EmailDeliveryStore.TaskSummary> summary = store.findTaskSummary(taskId);
    if (email.isEmpty() || summary.isEmpty()) {
      store.markProcessed(CONSUMER, dedupeKey);
      return;
    }
    EmailDeliveryStore.TaskSummary s = summary.get();
    EmailContent content =
        template.build(
            properties.getPublicUrl(),
            s.projectId(),
            taskId,
            s.projectKey(),
            s.taskNumber(),
            s.title());
    try {
      sender.send(email.get(), content);
    } catch (EmailPermanentException e) {
      log.warn(
          "Gorev e-postasi reddedildi (event={}, recipient={}): {}",
          eventId,
          recipientId,
          e.getMessage());
    }
    store.markProcessed(CONSUMER, dedupeKey);
  }

  private static UUID perRecipientEventId(UUID eventId, UUID recipientId) {
    return UUID.nameUUIDFromBytes((eventId + ":" + recipientId).getBytes(StandardCharsets.UTF_8));
  }

  private static List<UUID> mentionedUserIds(JsonNode payload) {
    List<UUID> ids = new ArrayList<>();
    payload
        .path("mentionedUserIds")
        .forEach(
            node -> {
              String value = node.asString(null);
              if (value != null && !value.isBlank()) {
                ids.add(UUID.fromString(value));
              }
            });
    return ids;
  }

  private static UUID uuid(JsonNode payload, String field) {
    String value = payload.path(field).asString(null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Eksik alan: " + field);
    }
    return UUID.fromString(value);
  }

  private static UUID uuidOrNull(JsonNode payload, String field) {
    String value = payload.path(field).asString(null);
    if (value == null || value.isBlank()) {
      return null;
    }
    return UUID.fromString(value);
  }
}
