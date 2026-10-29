package com.app.tracker.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.user.model.User;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserPrivacyNoticeTest {

  @Test
  void newUserStartsWithoutAcceptance() {
    User user = newUser();
    assertNull(user.getPrivacyNoticeVersion());
    assertNull(user.getPrivacyNoticeAcceptedAt());
  }

  @Test
  void sameVersionPreservesOriginalTimestamp() {
    User user = newUser();
    Instant first = Instant.parse("2026-10-29T08:00:00Z");
    user.acceptPrivacyNotice(PrivacyNotice.CURRENT_VERSION, first);
    user.acceptPrivacyNotice(PrivacyNotice.CURRENT_VERSION, first.plusSeconds(60));
    assertEquals(PrivacyNotice.CURRENT_VERSION, user.getPrivacyNoticeVersion());
    assertEquals(first, user.getPrivacyNoticeAcceptedAt());
  }

  @Test
  void newVersionReplacesVersionAndTimestampTogether() {
    User user = newUser();
    Instant first = Instant.parse("2026-10-28T08:00:00Z");
    Instant updated = first.plusSeconds(86400);
    user.acceptPrivacyNotice("2000-01-01", first);
    user.acceptPrivacyNotice(PrivacyNotice.CURRENT_VERSION, updated);
    assertEquals(PrivacyNotice.CURRENT_VERSION, user.getPrivacyNoticeVersion());
    assertEquals(updated, user.getPrivacyNoticeAcceptedAt());
  }

  private User newUser() {
    return User.newUser(UUID.randomUUID(), "user@tracker.local", "hash", "User");
  }
}
