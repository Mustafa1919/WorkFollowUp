package com.app.tracker.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.tracker.notification.service.NotificationMessageFormatter;
import com.app.tracker.notification.service.SlackMessageFormatter;
import com.app.tracker.task.model.TaskStatus;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class NotificationTurkishTextTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final NotificationMessageFormatter.TaskSummary TASK =
      new NotificationMessageFormatter.TaskSummary("PRJ", 7, "Türkçe görev");
  private static final String PREFIX =
      "PR birleştirildi ama görev Tamamlandı durumuna taşınmadı; önce bitmesi gereken: ";

  private String blocked(List<String> keys) {
    var payload = MAPPER.createObjectNode();
    var array = payload.putArray("blockerKeys");
    keys.forEach(array::add);
    return NotificationMessageFormatter.format("TASK_TRANSITION_BLOCKED", payload, TASK)
        .orElseThrow()[1];
  }

  @ParameterizedTest
  @CsvSource({
    "To Do,Yapılacak",
    "In Progress,Devam ediyor",
    "Review,İncelemede",
    "Done,Tamamlandı"
  })
  void K1_statusLabelsMatchGlossary(String key, String label) {
    assertThat(TaskStatus.label(key)).isEqualTo(label);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Custom status", "", "done", "İptal edildi"})
  void K1_unknownStatusIsPreserved(String value) {
    assertThat(TaskStatus.label(value)).isEqualTo(value);
  }

  @Test
  void K1_R1_labelsOnlyAffectDisplayAndPreserveEventKeys() {
    JsonNode payload = MAPPER.readTree("{\"oldStatus\":\"To Do\",\"newStatus\":\"Done\"}");
    String before = payload.toString();
    assertThat(
            NotificationMessageFormatter.format("TASK_STATUS_UPDATED", payload, TASK).orElseThrow())
        .containsExactly("PRJ-7: Türkçe görev", "Durum değişti: Yapılacak → Tamamlandı");
    assertThat(
            SlackMessageFormatter.format(
                    "TASK_STATUS_UPDATED",
                    payload,
                    new SlackMessageFormatter.TaskSummary("PRJ", 7, "Türkçe görev"))
                .orElseThrow())
        .isEqualTo("*PRJ-7* Türkçe görev: Yapılacak → Tamamlandı");
    assertThat(payload.toString()).isEqualTo(before);
    assertThat(
            List.of(TaskStatus.TO_DO, TaskStatus.IN_PROGRESS, TaskStatus.REVIEW, TaskStatus.DONE))
        .containsExactly("To Do", "In Progress", "Review", "Done");
  }

  @Test
  void K1_missingStatusIsNotDisplayed() {
    var empty = MAPPER.readTree("{}");
    assertThat(NotificationMessageFormatter.format("TASK_STATUS_UPDATED", empty, TASK)).isEmpty();
    assertThat(
            SlackMessageFormatter.format(
                "TASK_STATUS_UPDATED",
                empty,
                new SlackMessageFormatter.TaskSummary("PRJ", 7, "Türkçe görev")))
        .isEmpty();
  }

  @Test
  void K4_exactly500CharactersAreAccepted() {
    String key = "Ş".repeat(500 - PREFIX.length());
    assertThat(blocked(List.of(key))).isEqualTo(PREFIX + key).hasSize(500);
  }

  @Test
  void K4_overflowAt501CharactersIsSummarized() {
    String key = "Ş".repeat(501 - PREFIX.length());
    assertThat(blocked(List.of(key))).isEqualTo(PREFIX + "1 görev").hasSizeLessThanOrEqualTo(500);
  }

  @Test
  void K4_longListPreservesOrderAndRemainingCount() {
    List<String> keys = IntStream.rangeClosed(1, 200).mapToObj(i -> "PRJ-" + i).toList();
    String body = blocked(keys);
    assertThat(body).hasSizeLessThanOrEqualTo(500).startsWith(PREFIX + "PRJ-1, PRJ-2, PRJ-3");
    int shown = body.substring(PREFIX.length(), body.lastIndexOf(" ve ")).split(", ").length;
    assertThat(body).endsWith(" ve " + (200 - shown) + " görev daha");
    assertThat(body.substring(PREFIX.length(), body.lastIndexOf(" ve ")))
        .isEqualTo(String.join(", ", keys.subList(0, shown)));
  }

  @Test
  void K4_shortListIsNotSummarized() {
    assertThat(blocked(List.of("PRJ-1", "PRJ-2"))).isEqualTo(PREFIX + "PRJ-1, PRJ-2");
  }
}
