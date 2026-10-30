package com.app.tracker.telemetry;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Hata telemetrisine giren sir ve kisisel veriyi Kafka'ya yazilmadan once maskeler (SEC-09). Saf ve
 * durumsuz. Kurallar yalniz baglamsal desenlerdir; uzunluk/entropi tabanli genel bir kural YOKTUR
 * (errorType ve UUID'li yollar yanlis pozitif verirdi). Desenlerde ic ice niceleyici yoktur.
 */
@Component
public class TelemetryRedactor {

  public static final String EMAIL = "[email]";
  public static final String PAT = "[pat]";
  public static final String JWT = "[jwt]";
  public static final String BEARER = "Bearer [redacted]";
  public static final String REDACTED = "[redacted]";
  public static final String TOKEN_SEGMENT = "{token}";
  public static final String FAILED = "[redaction-failed]";

  /** Maskelemeden once mesaj bu uzunluga kirpilir (maliyet siniri). */
  static final int MAX_MESSAGE_LENGTH = 4000;

  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)+");
  private static final Pattern PAT_PATTERN = Pattern.compile("wf_pat_[A-Za-z0-9_\\-]+");
  private static final Pattern JWT_PATTERN =
      Pattern.compile("eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]*");
  private static final Pattern BEARER_PATTERN =
      Pattern.compile("\\bBearer\\s+\\S+", Pattern.CASE_INSENSITIVE);
  private static final Pattern QUERY_SECRET_PATTERN =
      Pattern.compile(
          "([?&])(token|code|key|secret|password|access_token|refresh_token)=[^&#\\s\"'<>]*",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern INVITATION_PATH_PATTERN =
      Pattern.compile("^(/(?:api/v1/)?invitations/)[^/]+", Pattern.CASE_INSENSITIVE);

  /** Mesaj: kirp, sorgu sirlari, Bearer, JWT, PAT, e-posta. Null null kalir. */
  public String redactMessage(String message) {
    if (message == null) {
      return null;
    }
    String text =
        message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) : message;
    text = QUERY_SECRET_PATTERN.matcher(text).replaceAll("$1$2=" + REDACTED);
    text = BEARER_PATTERN.matcher(text).replaceAll(BEARER);
    return redactIdentifiers(text);
  }

  /** errorType: yalniz e-posta / PAT / JWT. Baska degisiklik yok. */
  public String redactErrorType(String errorType) {
    if (errorType == null) {
      return null;
    }
    return redactIdentifiers(errorType);
  }

  /** Yol: sorgu ve hash atilir, davet token'i {token} olur. Diger yollar aynen. */
  public String redactPath(String path) {
    if (path == null) {
      return null;
    }
    int cut = path.length();
    int q = path.indexOf('?');
    int h = path.indexOf('#');
    if (q >= 0) {
      cut = q;
    }
    if (h >= 0 && h < cut) {
      cut = h;
    }
    String clean = path.substring(0, cut);
    clean = INVITATION_PATH_PATTERN.matcher(clean).replaceFirst("$1" + "\\" + TOKEN_SEGMENT);
    clean = BEARER_PATTERN.matcher(clean).replaceAll(BEARER);
    return redactIdentifiers(clean);
  }

  private static String redactIdentifiers(String text) {
    text = JWT_PATTERN.matcher(text).replaceAll(JWT);
    text = PAT_PATTERN.matcher(text).replaceAll(PAT);
    return EMAIL_PATTERN.matcher(text).replaceAll(EMAIL);
  }
}
