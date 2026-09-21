package com.app.tracker.integration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.integration.service.GithubEventInterpreter.StatusIntent;
import com.app.tracker.integration.service.GithubEventInterpreter.TaskReference;
import com.app.tracker.task.model.TaskStatus;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Saf eslesme kurallari: DB/Kafka/Spring yok. Kurallar sinifin javadoc'unda. */
class GithubEventInterpreterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode json(String text) {
    return MAPPER.readTree(text);
  }

  private static Optional<StatusIntent> push(String ref, String... commitMessages) {
    StringBuilder commits = new StringBuilder();
    for (String message : commitMessages) {
      if (commits.length() > 0) {
        commits.append(',');
      }
      commits.append("{\"message\":").append(MAPPER.writeValueAsString(message)).append('}');
    }
    return GithubEventInterpreter.interpret(
        "push", json("{\"ref\":\"" + ref + "\",\"commits\":[" + commits + "]}"));
  }

  private static Optional<StatusIntent> pullRequest(
      String action, boolean merged, boolean draft, String title, String body, String branch) {
    return GithubEventInterpreter.interpret(
        "pull_request",
        json(
            "{\"action\":\""
                + action
                + "\",\"pull_request\":{\"merged\":"
                + merged
                + ",\"draft\":"
                + draft
                + ",\"title\":"
                + MAPPER.writeValueAsString(title)
                + ",\"body\":"
                + MAPPER.writeValueAsString(body)
                + ",\"head\":{\"ref\":\""
                + branch
                + "\"}}}"));
  }

  private static Set<TaskReference> refs(Optional<StatusIntent> intent) {
    return intent.orElseThrow().references();
  }

  @Test
  void pushMovesReferencedTasksToInProgress() {
    Optional<StatusIntent> intent =
        push("refs/heads/main", "ENG-101 fix login", "wip ENG-7 and ENG-8");

    assertEquals(TaskStatus.IN_PROGRESS, intent.orElseThrow().targetStatus());
    assertEquals(
        Set.of(
            new TaskReference("ENG", 101),
            new TaskReference("ENG", 7),
            new TaskReference("ENG", 8)),
        refs(intent));
  }

  @Test
  void branchNameIsMatchedCaseInsensitivelyButCommitMessagesAreNot() {
    assertEquals(
        Set.of(new TaskReference("ENG", 12)), refs(push("refs/heads/feature/eng-12-login")));

    assertTrue(push("refs/heads/main", "bump utf-8 and sha-256 handling").isEmpty());
    assertTrue(push("refs/heads/main", "eng-12 lower case in message").isEmpty());
  }

  @Test
  void turkishLocaleDoesNotBreakKeyNormalisation() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));

      // Turkce'de "i".toUpperCase() "İ" olur; proje anahtari "INFRA" eslesmeli.
      assertEquals(Set.of(new TaskReference("INFRA", 3)), refs(push("refs/heads/infra-3-disk")));
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  void pushWithoutReferencesOrBranchDeletionIsIgnored() {
    assertTrue(push("refs/heads/main", "no task key here").isEmpty());
    assertTrue(
        GithubEventInterpreter.interpret(
                "push", json("{\"deleted\":true,\"ref\":\"refs/heads/eng-1-x\",\"commits\":[]}"))
            .isEmpty());
  }

  @Test
  void pullRequestLifecycleMapsToStatuses() {
    assertEquals(
        TaskStatus.REVIEW,
        pullRequest("opened", false, false, "ENG-5 add x", "", "x").orElseThrow().targetStatus());
    assertEquals(
        TaskStatus.REVIEW,
        pullRequest("reopened", false, false, "ENG-5 add x", "", "x").orElseThrow().targetStatus());
    assertEquals(
        TaskStatus.REVIEW,
        pullRequest("ready_for_review", false, true, "ENG-5", "", "x")
            .orElseThrow()
            .targetStatus());
    assertEquals(
        TaskStatus.DONE,
        pullRequest("closed", true, false, "ENG-5 add x", "", "x").orElseThrow().targetStatus());
  }

  @Test
  void draftPullRequestOnlyStartsWork() {
    assertEquals(
        TaskStatus.IN_PROGRESS,
        pullRequest("opened", false, true, "ENG-5 wip", "", "x").orElseThrow().targetStatus());
  }

  @Test
  void closedWithoutMergeAndNoiseActionsAreIgnored() {
    assertTrue(pullRequest("closed", false, false, "ENG-5", "", "x").isEmpty());
    for (String action :
        new String[] {"synchronize", "edited", "labeled", "assigned", "review_requested"}) {
      assertTrue(pullRequest(action, false, false, "ENG-5", "", "x").isEmpty(), action);
    }
  }

  @Test
  void pullRequestReferencesComeFromTitleBodyAndBranch() {
    Optional<StatusIntent> intent =
        pullRequest("closed", true, false, "Fix ENG-1", "Closes ENG-2\nand INFRA-9", "eng-3-login");

    assertEquals(
        Set.of(
            new TaskReference("ENG", 1),
            new TaskReference("ENG", 2),
            new TaskReference("INFRA", 9),
            new TaskReference("ENG", 3)),
        refs(intent));
  }

  @Test
  void referenceCountIsCappedToBoundDatabaseWork() {
    StringBuilder message = new StringBuilder();
    for (int i = 1; i <= GithubEventInterpreter.MAX_REFERENCES + 25; i++) {
      message.append("ENG-").append(i).append(' ');
    }

    assertEquals(
        GithubEventInterpreter.MAX_REFERENCES,
        refs(push("refs/heads/main", message.toString())).size());
  }

  @Test
  void keyShapeIsBounded() {
    assertTrue(
        push("refs/heads/main", "A-1 too short, LONGERTHANTEN-1 too long, X1-2222222222 huge")
            .isEmpty());
    assertFalse(push("refs/heads/main", "AB-1 ok").isEmpty());
  }

  @Test
  void unknownEventTypesAreIgnored() {
    assertTrue(
        GithubEventInterpreter.interpret("issues", json("{\"action\":\"opened\"}")).isEmpty());
  }
}
