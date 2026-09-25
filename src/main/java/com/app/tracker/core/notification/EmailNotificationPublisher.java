package com.app.tracker.core.notification;

import com.app.tracker.core.outbox.OutboxEventRepository;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.4.3 — e-posta gonderimi senkron yapilmaz, bir {@code
 * notification.email} Kafka olayi olarak uretilir; gercek gonderim Faz3 Notification Worker'in isi.
 *
 * <p>PHASE_2_DETAILED_DESIGN.md Bolum 2 — artik dogrudan {@code KafkaTemplate.send} DEGIL, Outbox
 * Pattern kullanir: yazma, cagiranin (AuthService) ayni {@code @Transactional} metodu icinde DB
 * yazimiyla ayni transaction'da olur; gercek Kafka gonderimi OutboxRelay'e devredilir. Dual-write
 * riski boylece kapanmistir (onceki surumdeki bilinen sinirlama artik gecerli degil).
 */
@Component
public class EmailNotificationPublisher {

  private static final String TOPIC = "notification.email";

  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  public EmailNotificationPublisher(
      OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
  }

  public void publishVerificationEmail(UUID userId, String purpose, String rawToken) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("userId", userId.toString());
    payload.put("token", rawToken);
    publish("email." + purpose.toLowerCase(Locale.ROOT), userId, payload);
  }

  public void publishSecurityAlert(UUID userId, String reason) {
    publish("email.security_alert", userId, Map.of("userId", userId.toString(), "reason", reason));
  }

  /**
   * Workspace daveti — davet edilen henuz kayitli OLMAYABILIR, bu yuzden alici {@code userId} ile
   * degil dogrudan {@code email} ile tasinir (bkz. EmailDeliveryService#resolveRecipient).
   */
  public void publishWorkspaceInvite(
      UUID invitationId, String email, String workspaceName, String role, String rawToken) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("email", email);
    payload.put("workspaceName", workspaceName);
    payload.put("role", role);
    payload.put("token", rawToken);
    publish("email.workspace_invite", invitationId, payload);
  }

  private void publish(String eventType, UUID aggregateId, Map<String, Object> payload) {
    outboxEventRepository.write(
        TOPIC, eventType, aggregateId, null, objectMapper.writeValueAsString(payload));
  }
}
