package com.app.tracker.notification.service;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * {@code task.events} olayindan Inbox bildiriminin baslik/govde metnini uretir (saf fonksiyon, I/O
 * yok) — SlackMessageFormatter ile AYNI desen, farkli hedef (Slack mrkdwn kacislama burada
 * GEREKMEZ: React metni {@code <span>...</span>} icine metin dugumu olarak basar, HTML olarak
 * yorumlamaz; XSS riski yalniz {@code dangerouslySetInnerHTML} ile olurdu, bu projede
 * kullanilmiyor).
 *
 * <p>Bildirime konu olay tipleri BILEREK dar tutuldu: RAKIP_ANALIZI.md gorev tanimi "durum
 * degisikligi, onay, bitis tarihi" diyor, etiket/sprint/story point degisikligi bildirim KONUSU
 * DEGIL (gurultu). {@code TASK_CREATED} de haric: olusturan zaten aktordur ve yeni gorevin baska
 * izleyicisi yoktur. V22: {@code TASK_ASSIGNED} eklendi (alicilar izleyiciler + yeni atanan).
 */
public final class NotificationMessageFormatter {

  public static final String TASK_STATUS_UPDATED = "TASK_STATUS_UPDATED";
  public static final String TASK_APPROVED = "TASK_APPROVED";
  public static final String TASK_APPROVAL_REVOKED = "TASK_APPROVAL_REVOKED";
  public static final String TASK_DUE_DATE_CHANGED = "TASK_DUE_DATE_CHANGED";
  public static final String TASK_ASSIGNED = "TASK_ASSIGNED";

  /**
   * {@code TASK_ASSIGNED}'in YENI atanana giden govdesi (digerleri {@link #format} govdesini alir).
   */
  public static final String ASSIGNED_TO_YOU = "Gorev size atandi.";

  static final int MAX_TITLE_LENGTH = 200;
  private static final String ARROW = " → ";

  public static boolean isNotifiable(String eventType) {
    return TASK_STATUS_UPDATED.equals(eventType)
        || TASK_APPROVED.equals(eventType)
        || TASK_APPROVAL_REVOKED.equals(eventType)
        || TASK_DUE_DATE_CHANGED.equals(eventType)
        || TASK_ASSIGNED.equals(eventType);
  }

  /** Gorevin bildirimde gorunen kimligi: proje anahtari + numara + baslik. */
  public record TaskSummary(String projectKey, int taskNumber, String title) {}

  private NotificationMessageFormatter() {}

  /**
   * @return (baslik, govde); olay tipi bildirime konu degilse veya gerekli alan yoksa bos
   */
  public static Optional<String[]> format(String eventType, JsonNode payload, TaskSummary task) {
    String key = task.projectKey() + "-" + task.taskNumber();
    String title = truncate(task.title());
    String header = key + ": " + title;
    if (TASK_STATUS_UPDATED.equals(eventType)) {
      String oldStatus = payload.path("oldStatus").asString(null);
      String newStatus = payload.path("newStatus").asString(null);
      if (oldStatus == null || newStatus == null) {
        return Optional.empty();
      }
      return Optional.of(new String[] {header, "Durum degisti: " + oldStatus + ARROW + newStatus});
    }
    if (TASK_APPROVED.equals(eventType)) {
      return Optional.of(new String[] {header, "Onaylandi."});
    }
    if (TASK_APPROVAL_REVOKED.equals(eventType)) {
      return Optional.of(new String[] {header, "Onayi geri alindi."});
    }
    if (TASK_DUE_DATE_CHANGED.equals(eventType)) {
      String newDueDate = payload.path("newDueDate").asString(null);
      String body = newDueDate == null ? "Bitis tarihi kaldirildi." : "Bitis tarihi: " + newDueDate;
      return Optional.of(new String[] {header, body});
    }
    if (TASK_ASSIGNED.equals(eventType)) {
      String newAssigneeId = payload.path("newAssigneeId").asString(null);
      String body = newAssigneeId == null ? "Atama kaldirildi." : "Atanan kisi degisti.";
      return Optional.of(new String[] {header, body});
    }
    return Optional.empty();
  }

  private static String truncate(String title) {
    return title.length() <= MAX_TITLE_LENGTH
        ? title
        : title.substring(0, MAX_TITLE_LENGTH) + "...";
  }
}
