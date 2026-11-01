package com.app.tracker.notification.email;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Turkce, duz metin + basit HTML sablonlari (saf fonksiyonlar, I/O yok — {@code
 * SlackMessageFormatter} ile ayni ilke). Kullanici kontrolundeki her deger ({@code title}) HTML
 * icin kacislanir ({@code &} ILK) — aksi halde gorev basligiyla enjeksiyon mumkun olurdu (Slack
 * mrkdwn kacislamasiyla AYNI gerekce, bkz. SlackMessageFormatter).
 */
public final class EmailTemplates {

  static final int MAX_TITLE_LENGTH = 200;

  private EmailTemplates() {}

  public static EmailContent verificationEmail(String publicUrl, String rawToken) {
    String link = publicUrl + "/verify-email?token=" + urlEncode(rawToken);
    String subject = "E-posta adresini doğrula";
    String text = "WorkFollowUp'a hoş geldin. E-posta adresini doğrulamak için: " + link;
    String html =
        wrap(
            "E-posta adresini doğrula",
            "WorkFollowUp'a hoş geldin. Devam etmek için e-posta adresini doğrula.",
            link,
            "E-postamı doğrula");
    return new EmailContent(subject, text, html);
  }

  public static EmailContent passwordResetEmail(String publicUrl, String rawToken) {
    String link = publicUrl + "/reset-password?token=" + urlEncode(rawToken);
    String subject = "Parola sıfırlama";
    String text =
        "Parolanı sıfırlamak istedin. Bu isteği sen yapmadıysan bu e-postayı yok sayabilirsin. "
            + "Yeni parola belirlemek için (30 dakika geçerli): "
            + link;
    String html =
        wrap(
            "Parola sıfırlama",
            "Parolanı sıfırlamak istedin. Bu isteği sen yapmadıysan bu e-postayı yok sayabilirsin. "
                + "Bağlantı 30 dakika geçerlidir.",
            link,
            "Yeni parola belirle");
    return new EmailContent(subject, text, html);
  }

  public static EmailContent securityAlertEmail(String reason) {
    String description = securityReasonText(reason);
    String subject = "Güvenlik uyarısı";
    String text = description + " Bu sen değilsen hemen parolanı değiştir.";
    String html =
        wrap(
            "Güvenlik uyarısı",
            description + " Bu sen değilsen hemen parolanı değiştir.",
            null,
            null);
    return new EmailContent(subject, text, html);
  }

  public static EmailContent taskAssignedEmail(
      String publicUrl,
      UUID projectId,
      UUID taskId,
      String projectKey,
      int taskNumber,
      String title) {
    String link = taskLink(publicUrl, projectId, taskId);
    String taskRef = escape(projectKey) + "-" + taskNumber;
    String subject =
        "Sana bir görev atandı: " + singleLineSubject(escape(projectKey)) + "-" + taskNumber;
    String safeTitle = escape(truncate(title));
    String text = taskRef + " \"" + title + "\" sana atandı: " + link;
    String html =
        wrap(
            "Sana bir görev atandı",
            "<strong>" + taskRef + "</strong> — " + safeTitle,
            link,
            "Görevi aç");
    return new EmailContent(subject, text, html);
  }

  public static EmailContent mentionEmail(
      String publicUrl,
      UUID projectId,
      UUID taskId,
      String projectKey,
      int taskNumber,
      String title) {
    String link = taskLink(publicUrl, projectId, taskId);
    String taskRef = escape(projectKey) + "-" + taskNumber;
    String subject =
        "Bir yorumda etiketlendin: " + singleLineSubject(escape(projectKey)) + "-" + taskNumber;
    String safeTitle = escape(truncate(title));
    String text = taskRef + " \"" + title + "\" görevindeki bir yorumda etiketlendin: " + link;
    String html =
        wrap(
            "Bir yorumda etiketlendin",
            "<strong>" + taskRef + "</strong> — " + safeTitle,
            link,
            "Yorumu aç");
    return new EmailContent(subject, text, html);
  }

  public static EmailContent workspaceInviteEmail(
      String publicUrl, String workspaceName, String role, String rawToken) {
    String link = publicUrl + "/invitations/" + urlEncode(rawToken);
    String safeWorkspace = escape(truncate(workspaceName));
    String roleLabel = roleLabel(role);
    String subject = singleLineSubject(safeWorkspace + " çalışma alanına davet edildin");
    String text =
        safeWorkspace
            + " çalışma alanına "
            + roleLabel
            + " rolüyle davet edildin (7 gün geçerli): "
            + link;
    String html =
        wrap(
            "Bir çalışma alanı davetin var",
            "<strong>"
                + safeWorkspace
                + "</strong> çalışma alanına <strong>"
                + roleLabel
                + "</strong> rolüyle davet edildin. Bağlantı 7 gün geçerlidir.",
            link,
            "Daveti görüntüle");
    return new EmailContent(subject, text, html);
  }

  private static String singleLineSubject(String subject) {
    return subject.replaceAll("[\\p{javaISOControl}\\u2028\\u2029 ]+", " ").trim();
  }

  private static String roleLabel(String role) {
    return switch (role) {
      case "WORKSPACE_ADMIN" -> "Yönetici";
      case "MANAGER" -> "Yönetmen";
      case "DEVELOPER" -> "Geliştirici";
      case "VIEWER" -> "İzleyici";
      default -> role;
    };
  }

  private static String taskLink(String publicUrl, UUID projectId, UUID taskId) {
    return publicUrl + "/projects/" + projectId + "?task=" + taskId;
  }

  private static String securityReasonText(String reason) {
    return switch (reason) {
      case "password_changed" -> "Parolan değiştirildi.";
      case "refresh_token_reuse_detected" ->
          "Hesabında şüpheli oturum etkinliği tespit edildi, tüm oturumların sonlandırıldı.";
      default -> "Hesabınla ilgili bir güvenlik olayı kaydedildi.";
    };
  }

  private static String wrap(String heading, String bodyHtml, String link, String buttonText) {
    StringBuilder html = new StringBuilder();
    html.append("<div style=\"font-family:sans-serif;max-width:480px\">");
    html.append("<h2>").append(escape(heading)).append("</h2>");
    html.append("<p>").append(bodyHtml).append("</p>");
    if (link != null) {
      html.append("<p><a href=\"")
          .append(escape(link))
          .append("\" style=\"display:inline-block;padding:10px 18px;background:#4f46e5;")
          .append("color:#fff;text-decoration:none;border-radius:8px\">")
          .append(escape(buttonText))
          .append("</a></p>");
      html.append("<p style=\"color:#666;font-size:12px\">").append(escape(link)).append("</p>");
    }
    html.append("</div>");
    return html.toString();
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String truncate(String title) {
    return title.length() <= MAX_TITLE_LENGTH
        ? title
        : title.substring(0, MAX_TITLE_LENGTH) + "...";
  }
}
