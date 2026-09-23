package com.app.tracker.notification.consumer;

import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.notification.dto.NotificationResponse;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.service.InboxFanoutService;
import com.app.tracker.notification.service.NotificationMessageFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PHASE_6_PRODUCT_FEATURES.md Bolum 4 — {@code task.events}'i kalici, PAYLASIMLI bir consumer group
 * ile ({@code notification-inbox}) tuketir; SlackNotificationConsumer ile AYNI iskelet (parse + tip
 * filtresi + TenantExecutor), farki satir yazdiktan SONRA (transaction commit olduktan sonra,
 * {@code tenantExecutor.runAs} donunce) alicilara STOMP push yapmasidir.
 *
 * <p><b>{@code auto.offset.reset=latest} BILEREK</b> (Cycle Time worker'in {@code earliest}'inin
 * TERSI, ama Slack worker'iyla AYNI gerekce): Inbox kullanici-yuzlu bir "yeni olay" akisidir, ilk
 * deploy'da veya group id degisiminde topic'teki TUM gecmisi (potansiyel binlerce eski olay)
 * herkesin gelen kutusuna bosaltmak kotu bir ilk deneyim + gurultu olurdu. Analitik/Cycle Time'in
 * {@code earliest}'i "dogru sayi" ureteceginden secilir, Inbox'un {@code latest}'i "gurultu
 * uretmeyecek" oldugundan.
 *
 * <p>Hata semantigi (bkz. KafkaConsumerConfig): bozuk mesaj {@link IllegalArgumentException}
 * (yeniden denemesiz DLT). WebSocket push'u en iyi-caba'dir (TaskEventBroadcastListener ile ayni
 * felsefe): push basarisiz olsa bile bildirim SATIRI zaten commit edilmistir, kullanici sayfayi
 * yeniledigi/REST'ten cektiginde gorur — push kaybi veri kaybi degildir.
 */
@Component
@Profile("!migrate")
public class InboxNotificationConsumer {

  private static final String USER_NOTIFICATIONS_DESTINATION = "/queue/notifications";

  private final ObjectMapper objectMapper;
  private final TenantExecutor tenantExecutor;
  private final InboxFanoutService fanoutService;
  private final SimpMessagingTemplate messagingTemplate;

  public InboxNotificationConsumer(
      ObjectMapper objectMapper,
      TenantExecutor tenantExecutor,
      InboxFanoutService fanoutService,
      SimpMessagingTemplate messagingTemplate) {
    this.objectMapper = objectMapper;
    this.tenantExecutor = tenantExecutor;
    this.fanoutService = fanoutService;
    this.messagingTemplate = messagingTemplate;
  }

  @KafkaListener(
      topics = "task.events",
      groupId = InboxFanoutService.CONSUMER,
      properties = {"auto.offset.reset=latest"})
  public void onMessage(String envelopeJson) {
    JsonNode envelope = parse(envelopeJson);
    String eventType = envelope.path("eventType").asString(null);
    if (!NotificationMessageFormatter.isNotifiable(eventType)) {
      return;
    }
    UUID eventId = uuid(envelope, "eventId");
    UUID workspaceId = uuid(envelope, "workspaceId");
    JsonNode payload = envelope.path("payload");

    // Consumer HTTP istegi disinda calisir, JWT yoktur: RLS baglamini olayin kendi
    // workspaceId'sinden kurariz. tenantExecutor.runAs donduğunde ic transaction ZATEN commit
    // olmustur (TenantExecutor kasitli olarak @Transactional degildir, bkz. javadoc'u) — push'u
    // burada yapmak "commit'ten SONRA bildir" garantisini saglar.
    List<Notification> created =
        tenantExecutor.runAs(
            workspaceId, () -> fanoutService.fanOut(eventId, workspaceId, eventType, payload));

    for (Notification notification : created) {
      messagingTemplate.convertAndSendToUser(
          notification.getUserId().toString(),
          USER_NOTIFICATIONS_DESTINATION,
          objectMapper.writeValueAsString(NotificationResponse.from(notification)));
    }
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
