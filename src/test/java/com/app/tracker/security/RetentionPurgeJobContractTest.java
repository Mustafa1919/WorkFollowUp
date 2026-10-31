package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.app.tracker.core.retention.RetentionPurgeJob;
import com.app.tracker.core.retention.RetentionPurgeRepository;
import com.app.tracker.core.retention.RetentionPurgeResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/** Docker-free contract and mutation tests; this clock belongs only to the manually created job. */
class RetentionPurgeJobContractTest {

  private static final Instant NOW = Instant.parse("2026-10-31T12:34:56Z");
  private RetentionPurgeRepository repository;
  private RetentionPurgeJob job;

  @BeforeEach
  void setUp() {
    repository = mock(RetentionPurgeRepository.class);
    job = new RetentionPurgeJob(repository, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void K1_R1_refreshCutoffIsInjectedNowWithoutSevenDayDelay() {
    job.purge();
    verify(repository).deleteExpiredRefreshTokens(NOW, 10_000);
  }

  @Test
  void K2_R2_verificationCutoffAllowsExactlyOneDayGrace() {
    job.purge();
    verify(repository).deleteStaleVerificationTokens(NOW.minus(1, ChronoUnit.DAYS), 10_000);
  }

  @Test
  void K3_R3_bothTelemetryCutoffsUse365Days() {
    job.purge();
    verify(repository).deleteUsageEventsBefore(NOW.minus(365, ChronoUnit.DAYS), 10_000);
    verify(repository).deleteErrorEventsBefore(NOW.minus(365, ChronoUnit.DAYS), 10_000);
  }

  @Test
  void K4_R4_emailOutboxCutoffUsesSevenDays() {
    job.purge();
    verify(repository).deleteUndeliveredEmailOutbox(NOW.minus(7, ChronoUnit.DAYS), 10_000);
  }

  @Test
  void K5_fullBatchesContinueAndAllFiveCountersSumIndependently() {
    when(repository.deleteExpiredRefreshTokens(any(), eq(10_000))).thenReturn(10_000, 10_000, 0);
    when(repository.deleteStaleVerificationTokens(any(), eq(10_000))).thenReturn(10_000, 0);
    when(repository.deleteUsageEventsBefore(any(), eq(10_000)))
        .thenReturn(10_000, 10_000, 10_000, 0);
    when(repository.deleteErrorEventsBefore(any(), eq(10_000))).thenReturn(10_000, 0);
    when(repository.deleteUndeliveredEmailOutbox(any(), eq(10_000))).thenReturn(10_000, 10_000, 0);

    assertEquals(new RetentionPurgeResult(20_000, 10_000, 30_000, 10_000, 20_000), job.purge());

    verify(repository, times(3)).deleteExpiredRefreshTokens(NOW, 10_000);
    verify(repository, times(2))
        .deleteStaleVerificationTokens(NOW.minus(1, ChronoUnit.DAYS), 10_000);
    verify(repository, times(4)).deleteUsageEventsBefore(NOW.minus(365, ChronoUnit.DAYS), 10_000);
    verify(repository, times(2)).deleteErrorEventsBefore(NOW.minus(365, ChronoUnit.DAYS), 10_000);
    verify(repository, times(3))
        .deleteUndeliveredEmailOutbox(NOW.minus(7, ChronoUnit.DAYS), 10_000);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void K5_shortNonzeroBatchesMustContinueUntilZeroForEveryTable() {
    // The interface explicitly requires zero termination, including partial batches.
    when(repository.deleteExpiredRefreshTokens(any(), eq(10_000))).thenReturn(3, 2, 0);
    when(repository.deleteStaleVerificationTokens(any(), eq(10_000))).thenReturn(4, 1, 0);
    when(repository.deleteUsageEventsBefore(any(), eq(10_000))).thenReturn(5, 2, 0);
    when(repository.deleteErrorEventsBefore(any(), eq(10_000))).thenReturn(6, 3, 0);
    when(repository.deleteUndeliveredEmailOutbox(any(), eq(10_000))).thenReturn(7, 4, 0);

    assertEquals(new RetentionPurgeResult(5, 5, 7, 9, 11), job.purge());
    verify(repository, times(3)).deleteExpiredRefreshTokens(NOW, 10_000);
    verify(repository, times(3))
        .deleteStaleVerificationTokens(NOW.minus(1, ChronoUnit.DAYS), 10_000);
    verify(repository, times(3)).deleteUsageEventsBefore(NOW.minus(365, ChronoUnit.DAYS), 10_000);
    verify(repository, times(3)).deleteErrorEventsBefore(NOW.minus(365, ChronoUnit.DAYS), 10_000);
    verify(repository, times(3))
        .deleteUndeliveredEmailOutbox(NOW.minus(7, ChronoUnit.DAYS), 10_000);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void K5_emptyTablesStopImmediatelyWithZeroCounters() {
    assertEquals(new RetentionPurgeResult(0, 0, 0, 0, 0), job.purge());
    verify(repository).deleteExpiredRefreshTokens(NOW, 10_000);
    verify(repository).deleteStaleVerificationTokens(NOW.minus(1, ChronoUnit.DAYS), 10_000);
    verify(repository).deleteUsageEventsBefore(NOW.minus(365, ChronoUnit.DAYS), 10_000);
    verify(repository).deleteErrorEventsBefore(NOW.minus(365, ChronoUnit.DAYS), 10_000);
    verify(repository).deleteUndeliveredEmailOutbox(NOW.minus(7, ChronoUnit.DAYS), 10_000);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void K5_eachBatchHasTransactionBoundaryAndJobHasOnlyNightlySchedule() throws Exception {
    assertFalse(RetentionPurgeJob.class.isAnnotationPresent(Transactional.class));
    var purge = RetentionPurgeJob.class.getMethod("purge");
    assertFalse(purge.isAnnotationPresent(Transactional.class));
    assertEquals("0 30 3 * * *", purge.getAnnotation(Scheduled.class).cron());
    for (String method :
        List.of(
            "deleteExpiredRefreshTokens",
            "deleteStaleVerificationTokens",
            "deleteUsageEventsBefore",
            "deleteErrorEventsBefore",
            "deleteUndeliveredEmailOutbox")) {
      assertTrue(
          RetentionPurgeRepository.class
              .getMethod(method, Instant.class, int.class)
              .isAnnotationPresent(Transactional.class),
          method);
    }
  }
}
