package com.app.tracker.integration.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.task.model.TaskStatus;
import org.junit.jupiter.api.Test;

/**
 * "Yalniz ileri" kurali: sirasiz/tekrar webhook teslimatinin (gec gelen bir push) tamamlanmis bir
 * gorevi geri sarmamasinin tek garantisi.
 */
class GithubEventProcessorForwardRuleTest {

  @Test
  void onlyStrictlyForwardTransitionsAreAllowed() {
    assertTrue(GithubEventProcessor.isForward(TaskStatus.TO_DO, TaskStatus.IN_PROGRESS));
    assertTrue(GithubEventProcessor.isForward(TaskStatus.TO_DO, TaskStatus.DONE));
    assertTrue(GithubEventProcessor.isForward(TaskStatus.IN_PROGRESS, TaskStatus.REVIEW));
    assertTrue(GithubEventProcessor.isForward(TaskStatus.REVIEW, TaskStatus.DONE));
  }

  @Test
  void sameOrBackwardTransitionsAreRejected() {
    assertFalse(GithubEventProcessor.isForward(TaskStatus.IN_PROGRESS, TaskStatus.IN_PROGRESS));
    assertFalse(GithubEventProcessor.isForward(TaskStatus.DONE, TaskStatus.IN_PROGRESS));
    assertFalse(GithubEventProcessor.isForward(TaskStatus.DONE, TaskStatus.REVIEW));
    assertFalse(GithubEventProcessor.isForward(TaskStatus.REVIEW, TaskStatus.IN_PROGRESS));
    assertFalse(GithubEventProcessor.isForward(TaskStatus.DONE, TaskStatus.TO_DO));
  }

  @Test
  void unknownStatusesAreNeverTouched() {
    assertFalse(GithubEventProcessor.isForward("Blocked", TaskStatus.DONE));
    assertFalse(GithubEventProcessor.isForward(TaskStatus.TO_DO, "Blocked"));
    assertFalse(GithubEventProcessor.isForward(null, TaskStatus.DONE));
  }
}
