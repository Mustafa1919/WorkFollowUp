package com.app.tracker.notification.email;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * {@code notification.email} topic'inin is mantigi ({@code EmailNotificationPublisher}'in ürettiği
 * {@code email.email_verification} / {@code email.password_reset} / {@code email.security_alert} /
 * {@code email.workspace_invite} olaylari). Guvenlik/hesap e-postalari kullanici tercihinden
 * BAGIMSIZDIR (yalniz gorev kaynakli atama/mention e-postalari {@code
 * NotificationPreferencesService} ile kapida, bkz. EmailTaskEventService) — bir kullanici parola
 * sifirlamayi "kapatamaz".
 *
 * <p>Akis SlackNotificationService ile AYNI: (1) daha once islendi mi? (kisa tx) (2) alici adresi
 * (kisa tx) (3) SMTP cagrisi — DB baglantisi TUTULMAZ (4) isaretle (kisa tx). At-least-once: 3 ile
 * 4 arasi cokus nadiren cift e-postaya yol acar.
 */
@Service
@Profile("!migrate")
public class EmailDeliveryService {

  public static final String CONSUMER = "notification-email";

  private static final Logger log = LoggerFactory.getLogger(EmailDeliveryService.class);

  private final EmailDeliveryStore store;
  private final EmailSender sender;
  private final EmailProperties properties;

  public EmailDeliveryService(
      EmailDeliveryStore store, EmailSender sender, EmailProperties properties) {
    this.store = store;
    this.sender = sender;
    this.properties = properties;
  }

  public enum Outcome {
    SENT,
    DUPLICATE,
    UNKNOWN_USER,
    SKIPPED,
    REJECTED
  }

  public Outcome handle(UUID eventId, String eventType, JsonNode payload) {
    if (store.alreadyProcessed(CONSUMER, eventId)) {
      return Outcome.DUPLICATE;
    }
    Optional<EmailContent> content = buildContent(eventType, payload);
    if (content.isEmpty()) {
      return Outcome.SKIPPED;
    }
    Optional<String> email = resolveRecipient(eventType, payload);
    if (email.isEmpty()) {
      return Outcome.UNKNOWN_USER;
    }
    try {
      sender.send(email.get(), content.get());
    } catch (EmailPermanentException e) {
      log.warn("E-posta reddedildi (event={}, type={}): {}", eventId, eventType, e.getMessage());
      return Outcome.REJECTED;
    }
    store.markProcessed(CONSUMER, eventId);
    return Outcome.SENT;
  }

  private Optional<EmailContent> buildContent(String eventType, JsonNode payload) {
    return switch (eventType) {
      case "email.email_verification" ->
          Optional.of(EmailTemplates.verificationEmail(properties.getPublicUrl(), token(payload)));
      case "email.password_reset" ->
          Optional.of(EmailTemplates.passwordResetEmail(properties.getPublicUrl(), token(payload)));
      case "email.security_alert" ->
          Optional.of(EmailTemplates.securityAlertEmail(payload.path("reason").asString("")));
      case "email.workspace_invite" ->
          Optional.of(
              EmailTemplates.workspaceInviteEmail(
                  properties.getPublicUrl(),
                  payload.path("workspaceName").asString(""),
                  payload.path("role").asString(""),
                  token(payload)));
      default -> Optional.empty();
    };
  }

  /**
   * {@code email.workspace_invite} istisna: davet edilen henuz kayitli OLMAYABILIR, {@code userId}
   * yok — alici adresi payload'daki {@code email} alanindan DOGRUDAN alinir (store'a bakilmaz).
   * Diger tum turler {@code userId} tasir, alici adresi kayitli kullanicidan cozulur.
   */
  private Optional<String> resolveRecipient(String eventType, JsonNode payload) {
    if ("email.workspace_invite".equals(eventType)) {
      String email = payload.path("email").asString(null);
      return email == null || email.isBlank() ? Optional.empty() : Optional.of(email);
    }
    return store.findUserEmail(uuid(payload, "userId"));
  }

  private static String token(JsonNode payload) {
    String value = payload.path("token").asString(null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Eksik alan: token");
    }
    return value;
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
