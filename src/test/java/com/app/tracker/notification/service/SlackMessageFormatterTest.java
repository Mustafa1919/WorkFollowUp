package com.app.tracker.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.notification.service.SlackMessageFormatter.TaskSummary;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SlackMessageFormatterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TaskSummary TASK = new TaskSummary("ENG", 12, "Login sayfasi");

  private static Optional<String> statusMessage(
      TaskSummary task, String oldStatus, String newStatus) {
    return SlackMessageFormatter.format(
        SlackMessageFormatter.TASK_STATUS_UPDATED,
        MAPPER.readTree(
            "{\"oldStatus\":" + json(oldStatus) + ",\"newStatus\":" + json(newStatus) + "}"),
        task);
  }

  private static String json(String value) {
    return value == null ? "null" : MAPPER.writeValueAsString(value);
  }

  @Test
  void statusChangeShowsKeyTitleAndTransition() {
    assertEquals(
        "*ENG-12* Login sayfasi: To Do → In Progress",
        statusMessage(TASK, "To Do", "In Progress").orElseThrow());
  }

  @Test
  void createdShowsKeyAndTitle() {
    assertEquals(
        "New task *ENG-12*: Login sayfasi",
        SlackMessageFormatter.format(
                SlackMessageFormatter.TASK_CREATED, MAPPER.readTree("{}"), TASK)
            .orElseThrow());
  }

  @Test
  void userControlledTextCannotInjectMentionsOrLinks() {
    TaskSummary hostile =
        new TaskSummary("E&G", 1, "<!channel> <@U123> <https://evil.example|click> & more");

    String message = statusMessage(hostile, "To <Do>", "Done").orElseThrow();

    assertFalse(message.contains("<"), "ham '<' kalmamali: Slack ozel sozdizimini baslatir");
    assertFalse(message.contains(">"));
    assertTrue(message.contains("&lt;!channel&gt;"));
    assertTrue(message.contains("&lt;@U123&gt;"));
    assertTrue(message.contains("&amp; more"));
    assertTrue(message.contains("E&amp;G-1"));
    assertTrue(message.contains("To &lt;Do&gt;"));
  }

  @Test
  void escapesAmpersandFirstSoEntitiesAreNotDoubleEscaped() {
    // "<" -> "&lt;" (ve & sonradan kacislanmaz); ham "&lt;" metni ise "&amp;lt;" olmali.
    assertEquals("&amp;lt;", SlackMessageFormatter.escape("&lt;"));
    assertEquals("&lt;", SlackMessageFormatter.escape("<"));
  }

  @Test
  void overlongTitleIsTruncated() {
    TaskSummary longTitle = new TaskSummary("ENG", 1, "x".repeat(1000));

    String message = statusMessage(longTitle, "To Do", "Done").orElseThrow();

    assertTrue(message.length() < 300);
    assertTrue(message.contains("..."));
  }

  @Test
  void missingStatusFieldsOrUninterestingEventsProduceNothing() {
    assertTrue(statusMessage(TASK, null, "Done").isEmpty());
    assertTrue(statusMessage(TASK, "To Do", null).isEmpty());
    assertTrue(
        SlackMessageFormatter.format("TASK_SPRINT_CHANGED", MAPPER.readTree("{}"), TASK).isEmpty());
  }

  @Test
  void onlyCreatedAndStatusUpdatedAreNotifiable() {
    assertTrue(SlackMessageFormatter.isNotifiable("TASK_CREATED"));
    assertTrue(SlackMessageFormatter.isNotifiable("TASK_STATUS_UPDATED"));
    assertFalse(SlackMessageFormatter.isNotifiable("TASK_SPRINT_CHANGED"));
    assertFalse(SlackMessageFormatter.isNotifiable("TASK_STORY_POINT_UPDATED"));
    assertFalse(SlackMessageFormatter.isNotifiable("SPRINT_COMPLETED"));
    assertFalse(SlackMessageFormatter.isNotifiable(null));
  }
}
