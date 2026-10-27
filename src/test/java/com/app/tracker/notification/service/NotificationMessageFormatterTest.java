package com.app.tracker.notification.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.notification.service.NotificationMessageFormatter.TaskSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NotificationMessageFormatterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TaskSummary TASK = new TaskSummary("ENG", 12, "Login sayfasi");

  private static Optional<String[]> blocked(String payloadJson) {
    return NotificationMessageFormatter.format(
        NotificationMessageFormatter.TASK_TRANSITION_BLOCKED, MAPPER.readTree(payloadJson), TASK);
  }

  @Test
  void transitionBlockedIsNotifiable() {
    assertTrue(
        NotificationMessageFormatter.isNotifiable(
            NotificationMessageFormatter.TASK_TRANSITION_BLOCKED));
  }

  @Test
  void transitionBlockedListsBlockerKeys() {
    assertArrayEquals(
        new String[] {
          "ENG-12: Login sayfasi",
          "PR merge edildi ama gorev Done'a tasinmadi; once bitmesi gereken: ENG-2, OPS-7"
        },
        blocked("{\"blockerKeys\":[\"ENG-2\",\"OPS-7\"]}").orElseThrow());
  }

  @Test
  void longBlockerListIsSummarizedWithinBodyLimit() {
    List<String> keys = new ArrayList<>();
    for (int i = 1; i <= 200; i++) {
      keys.add("PROJECTKEY-" + (1000 + i));
    }
    String body = NotificationMessageFormatter.transitionBlockedBody(keys);
    assertTrue(body.length() <= NotificationMessageFormatter.MAX_BODY_LENGTH, body);
    assertTrue(body.contains("PROJECTKEY-1001, PROJECTKEY-1002"), body);
    assertTrue(body.matches(".* ve \\d+ gorev daha$"), body);
    int shown = body.split(", ").length;
    assertEquals(200 - shown + " gorev daha", body.substring(body.lastIndexOf(" ve ") + 4));
  }

  @Test
  void exactlyFittingListIsNotSummarized() {
    String prefix = "PR merge edildi ama gorev Done'a tasinmadi; once bitmesi gereken: ";
    String key = "K".repeat(NotificationMessageFormatter.MAX_BODY_LENGTH - prefix.length());
    String body = NotificationMessageFormatter.transitionBlockedBody(List.of(key));
    assertEquals(prefix + key, body);
    assertEquals(NotificationMessageFormatter.MAX_BODY_LENGTH, body.length());
  }

  @Test
  void singleOversizedKeyFallsBackToCountOnly() {
    String body =
        NotificationMessageFormatter.transitionBlockedBody(List.of("K".repeat(600), "ENG-2"));
    assertTrue(body.length() <= NotificationMessageFormatter.MAX_BODY_LENGTH, body);
    assertTrue(body.endsWith("once bitmesi gereken: 2 gorev"), body);
  }

  @Test
  void transitionBlockedWithMissingBlockerKeysIsEmpty() {
    assertTrue(blocked("{}").isEmpty());
  }

  @Test
  void transitionBlockedWithEmptyBlockerKeysIsEmpty() {
    assertTrue(blocked("{\"blockerKeys\":[]}").isEmpty());
  }

  @Test
  void transitionBlockedWithNonArrayBlockerKeysIsEmpty() {
    assertTrue(blocked("{\"blockerKeys\":\"ENG-2\"}").isEmpty());
  }

  @Test
  void transitionBlockedWithNonStringElementIsEmpty() {
    assertTrue(blocked("{\"blockerKeys\":[\"ENG-2\",5]}").isEmpty());
    assertTrue(blocked("{\"blockerKeys\":[\"ENG-2\",\" \"]}").isEmpty());
  }
}
