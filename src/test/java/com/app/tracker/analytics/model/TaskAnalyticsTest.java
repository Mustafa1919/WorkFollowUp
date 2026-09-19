package com.app.tracker.analytics.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.task.model.TaskStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Cycle Time tanimlarinin (bkz. {@link TaskAnalytics} javadoc'u) saf mantik testi — DB/Kafka
 * gerektirmez, bu yuzden kenar durumlar burada tek tek sabitlenir.
 */
class TaskAnalyticsTest {

  private static final Instant T0 = Instant.parse("2026-09-01T09:00:00Z");

  private static TaskAnalytics fresh() {
    return TaskAnalytics.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }

  @Test
  void cycleTimeIsDoneMinusFirstInProgress() {
    TaskAnalytics a = fresh();
    a.applyStatusChange(TaskStatus.IN_PROGRESS, T0);
    a.applyStatusChange(TaskStatus.REVIEW, T0.plusSeconds(3600));
    a.applyStatusChange(TaskStatus.DONE, T0.plusSeconds(7200));

    assertEquals(T0, a.getFirstInProgressAt());
    assertEquals(T0.plusSeconds(7200), a.getDoneAt());
    assertEquals(7200L, a.getCycleTimeSeconds());
  }

  @Test
  void reenteringInProgressDoesNotResetTheStart() {
    TaskAnalytics a = fresh();
    a.applyStatusChange(TaskStatus.IN_PROGRESS, T0);
    a.applyStatusChange(TaskStatus.TO_DO, T0.plusSeconds(600));
    a.applyStatusChange(TaskStatus.IN_PROGRESS, T0.plusSeconds(1200));
    a.applyStatusChange(TaskStatus.DONE, T0.plusSeconds(3600));

    assertEquals(T0, a.getFirstInProgressAt());
    assertEquals(3600L, a.getCycleTimeSeconds(), "rework suresi de cycle time'a dahildir");
  }

  @Test
  void skippingInProgressLeavesCycleTimeUndefinedButRecordsDone() {
    TaskAnalytics a = fresh();
    a.applyStatusChange(TaskStatus.DONE, T0);

    assertEquals(T0, a.getDoneAt());
    assertNull(a.getCycleTimeSeconds());
  }

  @Test
  void leavingDoneClearsCompletionAndReclosingRecomputes() {
    TaskAnalytics a = fresh();
    a.applyStatusChange(TaskStatus.IN_PROGRESS, T0);
    a.applyStatusChange(TaskStatus.DONE, T0.plusSeconds(100));
    a.applyStatusChange(TaskStatus.IN_PROGRESS, T0.plusSeconds(200));

    assertNull(a.getDoneAt(), "yeniden acilan gorev tamamlanmis sayilmaz");
    assertNull(a.getCycleTimeSeconds());

    a.applyStatusChange(TaskStatus.DONE, T0.plusSeconds(500));
    assertEquals(500L, a.getCycleTimeSeconds());
  }

  @Test
  void staleEventCannotRewindState() {
    TaskAnalytics a = fresh();
    a.applyStatusChange(TaskStatus.IN_PROGRESS, T0);
    a.applyStatusChange(TaskStatus.DONE, T0.plusSeconds(1000));

    boolean applied = a.applyStatusChange(TaskStatus.IN_PROGRESS, T0.plusSeconds(500));

    assertFalse(applied);
    assertEquals(T0.plusSeconds(1000), a.getDoneAt(), "eski tarihli olay durumu geri saramaz");
    assertEquals(1000L, a.getCycleTimeSeconds());
  }

  @Test
  void eventAtTheSameInstantIsStillApplied() {
    TaskAnalytics a = fresh();
    assertTrue(a.applyStatusChange(TaskStatus.IN_PROGRESS, T0));
    assertTrue(a.applyStatusChange(TaskStatus.DONE, T0));
    assertEquals(0L, a.getCycleTimeSeconds());
  }
}
