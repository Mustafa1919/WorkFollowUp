package com.app.tracker.notification.service;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Notification Worker'in is mantigi (PHASE_3 Bolum 3). Tenant baglami cagiran tarafindan ({@code
 * TenantExecutor}) olayin workspaceId'sinden kurulmus olmalidir.
 *
 * <p>Akis, transaction'lar DISINDA dis cagri yapacak sekilde: (1) adres var mi? (kisa tx) (2) daha
 * once gonderildi mi? (kisa tx) (3) gorev ozeti (kisa tx) (4) Slack HTTP cagrisi — DB baglantisi
 * tutulmaz (5) teslimat isareti (kisa tx). Semantik at-least-once: 4 ile 5 arasinda cokus tekrar
 * gonderime yol acar; bir bildirimin nadiren iki kez gelmesi, hic gelmemesinden (veya DB
 * baglantisini dis cagri boyunca tutmaktan) daha ucuz bir bedeldir.
 */
@Service
@Profile("!migrate")
public class SlackNotificationService {

  public static final String CONSUMER = "notification-slack";

  /** Testlerin ve log'un ayirt edebilmesi icin islemin sonucu. */
  public enum Outcome {
    SENT,
    NOT_CONFIGURED,
    DUPLICATE,
    SKIPPED,
    REJECTED_BY_SLACK
  }

  private static final Logger log = LoggerFactory.getLogger(SlackNotificationService.class);

  private final SlackIntegrationService integrationService;
  private final SlackDeliveryStore deliveryStore;
  private final SlackSender sender;

  public SlackNotificationService(
      SlackIntegrationService integrationService,
      SlackDeliveryStore deliveryStore,
      SlackSender sender) {
    this.integrationService = integrationService;
    this.deliveryStore = deliveryStore;
    this.sender = sender;
  }

  /**
   * @throws SlackTransientException gecici gonderim hatasi (Kafka error handler yeniden dener)
   */
  public Outcome handle(UUID workspaceId, UUID eventId, String eventType, JsonNode payload) {
    if (!SlackMessageFormatter.isNotifiable(eventType)) {
      return Outcome.SKIPPED;
    }
    Optional<URI> target = integrationService.findActiveWebhookUrl();
    if (target.isEmpty()) {
      return Outcome.NOT_CONFIGURED;
    }
    if (deliveryStore.alreadyDelivered(eventId)) {
      return Outcome.DUPLICATE;
    }
    UUID taskId = uuid(payload, "taskId");
    Optional<String> message =
        deliveryStore
            .findTaskSummary(taskId)
            .flatMap(summary -> SlackMessageFormatter.format(eventType, payload, summary));
    if (message.isEmpty()) {
      return Outcome.SKIPPED;
    }

    try {
      sender.send(target.get(), message.get());
    } catch (SlackPermanentException e) {
      // Adres/kanal olu: yeniden deneme ve DLT anlamsiz. Adres log'a YAZILMAZ.
      log.warn(
          "Slack bildirimi reddedildi (workspace={}, event={}): {}",
          workspaceId,
          eventId,
          e.getMessage());
      return Outcome.REJECTED_BY_SLACK;
    }
    deliveryStore.markDelivered(eventId);
    return Outcome.SENT;
  }

  private static UUID uuid(JsonNode payload, String field) {
    String value = payload.path(field).asString(null);
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
