package com.app.tracker.notification.service;

import java.util.ArrayList;
import java.util.List;
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
 * izleyicisi yoktur. V22: {@code TASK_ASSIGNED} eklendi (alicilar izleyiciler + yeni atanan). V23:
 * {@code COMMENT_ADDED}/{@code COMMENT_MENTION} eklendi (bkz. InboxFanoutService — ayni yoruma iki
 * kez bildirim gitmesin diye COMMENT_ADDED alicilarindan mention edilenler CIKARILIR).
 */
public final class NotificationMessageFormatter {

  public static final String TASK_STATUS_UPDATED = "TASK_STATUS_UPDATED";
  public static final String TASK_APPROVED = "TASK_APPROVED";
  public static final String TASK_APPROVAL_REVOKED = "TASK_APPROVAL_REVOKED";
  public static final String TASK_DUE_DATE_CHANGED = "TASK_DUE_DATE_CHANGED";
  public static final String TASK_ASSIGNED = "TASK_ASSIGNED";
  public static final String COMMENT_ADDED = "COMMENT_ADDED";
  public static final String COMMENT_MENTION = "COMMENT_MENTION";

  /** ADR-0020: PR merge edildi ama acik bloklayan yuzunden Done'a tasinmadi. */
  public static final String TASK_TRANSITION_BLOCKED = "TASK_TRANSITION_BLOCKED";

  /**
   * {@code TASK_ASSIGNED}'in YENI atanana giden govdesi (digerleri {@link #format} govdesini alir).
   */
  public static final String ASSIGNED_TO_YOU = "Gorev size atandi.";

  static final int MAX_TITLE_LENGTH = 200;

  /** {@code notifications.body VARCHAR(500)} (V18) — asilirsa insert kalici hata verir (DLT). */
  static final int MAX_BODY_LENGTH = 500;

  private static final String BLOCKED_PREFIX =
      "PR merge edildi ama gorev Done'a tasinmadi; once bitmesi gereken: ";
  private static final String ARROW = " → ";

  public static boolean isNotifiable(String eventType) {
    return TASK_STATUS_UPDATED.equals(eventType)
        || TASK_APPROVED.equals(eventType)
        || TASK_APPROVAL_REVOKED.equals(eventType)
        || TASK_DUE_DATE_CHANGED.equals(eventType)
        || TASK_ASSIGNED.equals(eventType)
        || COMMENT_ADDED.equals(eventType)
        || COMMENT_MENTION.equals(eventType)
        || TASK_TRANSITION_BLOCKED.equals(eventType);
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
    if (COMMENT_ADDED.equals(eventType)) {
      return Optional.of(new String[] {header, "Yeni yorum eklendi."});
    }
    if (COMMENT_MENTION.equals(eventType)) {
      return Optional.of(new String[] {header, "Yorumda sizden bahsedildi."});
    }
    if (TASK_TRANSITION_BLOCKED.equals(eventType)) {
      return blockerKeys(payload.path("blockerKeys"))
          .map(keys -> new String[] {header, transitionBlockedBody(keys)});
    }
    return Optional.empty();
  }

  /**
   * Govde {@link #MAX_BODY_LENGTH}'i ASLA asmaz: anahtarlar sigdigi kadar eklenir, kalan " ve N
   * gorev daha" ile ozetlenir; tek anahtar bile sigmazsa yalniz sayi yazilir.
   */
  static String transitionBlockedBody(List<String> keys) {
    int total = keys.size();
    String best = BLOCKED_PREFIX + total + " gorev";
    StringBuilder joined = new StringBuilder();
    for (int count = 1; count <= total; count++) {
      if (count > 1) {
        joined.append(", ");
      }
      joined.append(keys.get(count - 1));
      int remaining = total - count;
      int length =
          BLOCKED_PREFIX.length()
              + joined.length()
              + (remaining == 0 ? 0 : (" ve " + remaining + " gorev daha").length());
      if (length > MAX_BODY_LENGTH) {
        break;
      }
      best = BLOCKED_PREFIX + joined + (remaining == 0 ? "" : " ve " + remaining + " gorev daha");
    }
    return best;
  }

  /** Bos olmayan, yalniz bos olmayan metin elemanli dizi -> anahtar listesi; aksi halde bos. */
  private static Optional<List<String>> blockerKeys(JsonNode node) {
    if (!node.isArray() || node.isEmpty()) {
      return Optional.empty();
    }
    List<String> keys = new ArrayList<>();
    for (JsonNode element : node) {
      if (!element.isString() || element.asString().isBlank()) {
        return Optional.empty();
      }
      keys.add(element.asString());
    }
    return Optional.of(keys);
  }

  private static String truncate(String title) {
    return title.length() <= MAX_TITLE_LENGTH
        ? title
        : title.substring(0, MAX_TITLE_LENGTH) + "...";
  }
}
