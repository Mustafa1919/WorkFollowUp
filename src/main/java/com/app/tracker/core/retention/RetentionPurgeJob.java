package com.app.tracker.core.retention;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SEC-10 — aydinlatma metninin vaat ettigi saklama surelerini gece, parca parca silerek uygular.
 * Transaction siniri {@link RetentionPurgeRepository}'dedir (bkz. OutboxCleanupJob: bu sinifin
 * metotlarina {@code @Transactional} koymak self-invocation nedeniyle yanlis olurdu).
 */
@Component
@Profile("!migrate")
public class RetentionPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);
  static final int BATCH_SIZE = 10_000;
  private static final Duration VERIFICATION_GRACE = Duration.ofDays(1);
  private static final Duration TELEMETRY_RETENTION = Duration.ofDays(365);
  private static final Duration EMAIL_OUTBOX_RETENTION = Duration.ofDays(7);

  private final RetentionPurgeRepository repository;
  private final Clock clock;

  public RetentionPurgeJob(RetentionPurgeRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Scheduled(cron = "0 30 3 * * *")
  public RetentionPurgeResult purge() {
    Instant now = clock.instant();
    RetentionPurgeResult result =
        new RetentionPurgeResult(
            loop(now, repository::deleteExpiredRefreshTokens),
            loop(now.minus(VERIFICATION_GRACE), repository::deleteStaleVerificationTokens),
            loop(now.minus(TELEMETRY_RETENTION), repository::deleteUsageEventsBefore),
            loop(now.minus(TELEMETRY_RETENTION), repository::deleteErrorEventsBefore),
            loop(now.minus(EMAIL_OUTBOX_RETENTION), repository::deleteUndeliveredEmailOutbox));
    log.info(
        "Retention purge: refreshTokens={}, verificationTokens={}, usageEvents={}, errorEvents={},"
            + " undeliveredEmailOutbox={}",
        result.refreshTokens(),
        result.verificationTokens(),
        result.usageEvents(),
        result.errorEvents(),
        result.undeliveredEmailOutbox());
    return result;
  }

  private static int loop(Instant cutoff, BiFunction<Instant, Integer, Integer> deleteBatch) {
    int total = 0;
    int deleted;
    do {
      deleted = deleteBatch.apply(cutoff, BATCH_SIZE);
      total += deleted;
    } while (deleted > 0);
    return total;
  }
}
