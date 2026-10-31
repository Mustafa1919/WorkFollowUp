package com.app.tracker.comment.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.web.CursorInstantCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** TaskCursor (task/service) ile AYNI kodlama — keyset sayfalama, Base64 (createdAt, id). */
record CommentCursor(Instant createdAt, UUID id) {

  String encode() {
    String raw = CursorInstantCodec.encode(createdAt) + ":" + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  static CommentCursor decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = raw.split(":", 2);
      return new CommentCursor(CursorInstantCodec.decode(parts[0]), UUID.fromString(parts[1]));
    } catch (RuntimeException e) {
      throw new BusinessRuleException("Gecersiz cursor.");
    }
  }
}
