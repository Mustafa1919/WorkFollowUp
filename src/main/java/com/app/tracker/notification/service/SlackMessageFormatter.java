package com.app.tracker.notification.service;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * {@code task.events} olayindan Slack mesaj metnini uretir (saf fonksiyon, I/O yok).
 *
 * <p>Kullanici kontrolundeki her deger (baslik, durum) Slack'in ozel karakterleri icin kacislanir:
 * Slack mrkdwn'de {@code <!channel>}, {@code <@U123>} ve {@code <http://...|metin>} sozdizimi
 * {@code <} ile baslar; {@code &}, {@code <}, {@code >} kacislanmazsa gorev basligiyla tum kanali
 * etiketlemek veya aldatici baglanti gondermek mumkun olurdu (bildirim enjeksiyonu).
 */
public final class SlackMessageFormatter {

  public static final String TASK_CREATED = "TASK_CREATED";
  public static final String TASK_STATUS_UPDATED = "TASK_STATUS_UPDATED";

  static final int MAX_TITLE_LENGTH = 200;
  private static final String ARROW = " → ";

  /** Bildirim uretilen olay tipleri; digerleri (sprint, story point) sessizce atlanir. */
  public static boolean isNotifiable(String eventType) {
    return TASK_CREATED.equals(eventType) || TASK_STATUS_UPDATED.equals(eventType);
  }

  /** Gorevin bildirimde gorunen kimligi: proje anahtari + numara + baslik. */
  public record TaskSummary(String projectKey, int taskNumber, String title) {}

  private SlackMessageFormatter() {}

  /**
   * @return mesaj; olay tipi bildirime konu degilse veya gerekli alan yoksa bos
   */
  public static Optional<String> format(String eventType, JsonNode payload, TaskSummary task) {
    String key = "*" + escape(task.projectKey()) + "-" + task.taskNumber() + "*";
    String title = escape(truncate(task.title()));
    if (TASK_CREATED.equals(eventType)) {
      return Optional.of("New task " + key + ": " + title);
    }
    if (TASK_STATUS_UPDATED.equals(eventType)) {
      String oldStatus = payload.path("oldStatus").asString(null);
      String newStatus = payload.path("newStatus").asString(null);
      if (oldStatus == null || newStatus == null) {
        return Optional.empty();
      }
      return Optional.of(key + " " + title + ": " + escape(oldStatus) + ARROW + escape(newStatus));
    }
    return Optional.empty();
  }

  static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String truncate(String title) {
    return title.length() <= MAX_TITLE_LENGTH
        ? title
        : title.substring(0, MAX_TITLE_LENGTH) + "...";
  }
}
