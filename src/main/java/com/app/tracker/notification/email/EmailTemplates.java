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
    String subject = "E-posta adresini dogrula";
    String text = "WorkFollowUp'a hos geldin. E-posta adresini dogrulamak icin: " + link;
    String html =
        wrap(
            "E-posta adresini dogrula",
            "WorkFollowUp'a hos geldin. Devam etmek icin e-posta adresini dogrula.",
            link,
            "E-postami dogrula");
    return new EmailContent(subject, text, html);
  }

  public static EmailContent passwordResetEmail(String publicUrl, String rawToken) {
    String link = publicUrl + "/reset-password?token=" + urlEncode(rawToken);
    String subject = "Parola sifirlama";
    String text =
        "Parolani sifirlamak istedin. Bu istegi sen yapmadiysan bu e-postayi yok sayabilirsin. "
            + "Yeni parola belirlemek icin (30 dakika gecerli): "
            + link;
    String html =
        wrap(
            "Parola sifirlama",
            "Parolani sifirlamak istedin. Bu istegi sen yapmadiysan bu e-postayi yok sayabilirsin. "
                + "Baglanti 30 dakika gecerlidir.",
            link,
            "Yeni parola belirle");
    return new EmailContent(subject, text, html);
  }

  public static EmailContent securityAlertEmail(String reason) {
    String description = securityReasonText(reason);
    String subject = "Guvenlik uyarisi";
    String text = description + " Bu sen degilsen hemen parolani degistir.";
    String html =
        wrap(
            "Guvenlik uyarisi",
            description + " Bu sen degilsen hemen parolani degistir.",
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
    String subject = "Sana bir gorev atandi: " + taskRef;
    String safeTitle = escape(truncate(title));
    String text = taskRef + " \"" + title + "\" sana atandi: " + link;
    String html =
        wrap(
            "Sana bir gorev atandi",
            "<strong>" + taskRef + "</strong> — " + safeTitle,
            link,
            "Gorevi ac");
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
    String subject = "Bir yorumda etiketlendin: " + taskRef;
    String safeTitle = escape(truncate(title));
    String text = taskRef + " \"" + title + "\" gorevindeki bir yorumda etiketlendin: " + link;
    String html =
        wrap(
            "Bir yorumda etiketlendin",
            "<strong>" + taskRef + "</strong> — " + safeTitle,
            link,
            "Yorumu ac");
    return new EmailContent(subject, text, html);
  }

  private static String taskLink(String publicUrl, UUID projectId, UUID taskId) {
    return publicUrl + "/projects/" + projectId + "?task=" + taskId;
  }

  private static String securityReasonText(String reason) {
    return switch (reason) {
      case "password_changed" -> "Parolan degistirildi.";
      case "refresh_token_reuse_detected" ->
          "Hesabinda supheli oturum etkinligi tespit edildi, tum oturumlarin sonlandirildi.";
      default -> "Hesabinla ilgili bir guvenlik olayi kaydedildi.";
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
