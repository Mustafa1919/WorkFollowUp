package com.app.tracker.notification.service;

import com.app.tracker.core.exception.BusinessRuleException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** TaskCursor (PHASE_1_DETAILED_DESIGN.md Bolum 4.1) ile AYNI desen: (createdAt, id) kodlamasi. */
record NotificationCursor(Instant createdAt, UUID id) {

  String encode() {
    String raw = createdAt.toEpochMilli() + ":" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  static NotificationCursor decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = raw.split(":", 2);
      return new NotificationCursor(
          Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
    } catch (RuntimeException e) {
      throw new BusinessRuleException("Gecersiz cursor.");
    }
  }
}
