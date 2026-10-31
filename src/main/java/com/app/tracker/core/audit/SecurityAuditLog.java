package com.app.tracker.core.audit;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SEC-23 -- guvenlik olaylarinin tek yazim noktasi. {@code security.audit} logger'ina sabit
 * bicimli, sir ve e-posta icermeyen tek satir yazar: {@code security_audit event=<EVENT>
 * actor=<uuid|-> k1=v1 k2=v2} (anahtarlar alfabetik). Degerler {@code [A-Za-z0-9._:@/-]} disindaki
 * her karakter {@code _} yapilarak ve 100 karaktere kirpilarak yazilir (log enjeksiyonu yok). Yazim
 * hatasi asil akisi ASLA etkilemez.
 */
@Component
public class SecurityAuditLog {

  static final String LOGGER_NAME = "security.audit";
  static final int MAX_VALUE_LENGTH = 100;

  private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER_NAME);

  public void record(
      SecurityAuditEvent event, @Nullable UUID actorId, Map<String, String> attributes) {
    try {
      String actor = actorId == null ? "-" : actorId.toString();
      Map<String, String> sorted = new TreeMap<>();
      if (attributes != null) {
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
          sorted.put(entry.getKey(), sanitize(entry.getValue()));
        }
      }
      StringBuilder line =
          new StringBuilder("security_audit event=").append(event.name()).append(" actor=");
      line.append(actor);
      for (Map.Entry<String, String> entry : sorted.entrySet()) {
        line.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
      }
      LoggingEventBuilder builder =
          AUDIT.atInfo().addKeyValue("event", event.name()).addKeyValue("actor", actor);
      for (Map.Entry<String, String> entry : sorted.entrySet()) {
        builder = builder.addKeyValue(entry.getKey(), entry.getValue());
      }
      builder.log(line.toString());
    } catch (RuntimeException e) {
      // Audit asil istegi bozmaz.
    }
  }

  /**
   * Cagri noktalari icin: audit (ya da yerine konan bir bilesen) istisna atsa bile asil akis
   * etkilenmez.
   */
  public static void safeRecord(
      @Nullable SecurityAuditLog auditLog,
      SecurityAuditEvent event,
      @Nullable UUID actorId,
      Map<String, String> attributes) {
    try {
      if (auditLog != null) {
        auditLog.record(event, actorId, attributes);
      }
    } catch (RuntimeException e) {
      // Audit asil istegi bozmaz.
    }
  }

  /** Kimlikli istegin kullanici id'si; kimliksiz ya da UUID olmayan principal icin {@code null}. */
  public static @Nullable UUID currentActorOrNull() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }
    return authentication.getPrincipal() instanceof UUID id ? id : null;
  }

  static String sanitize(@Nullable String value) {
    if (value == null) {
      return "-";
    }
    String cleaned = value.replaceAll("[^A-Za-z0-9._:@/-]", "_");
    return cleaned.length() > MAX_VALUE_LENGTH ? cleaned.substring(0, MAX_VALUE_LENGTH) : cleaned;
  }
}
