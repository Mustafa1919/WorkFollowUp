package com.app.tracker.core.outbox;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 2, madde 4 — islenmis outbox kayitlarini 7 gun sonra, 10.000'lik
 * parcalar halinde siler (tek dev DELETE'in uzun sureli kilit + WAL sismesi riskini onlemek icin).
 * Her batch'in transaction siniri {@code OutboxEventRepository.deleteProcessedBatch}'te; bu sinifin
 * kendi metotlarina {@code @Transactional} koymak YANLIS olurdu (self-invocation Spring AOP
 * proxy'sini atlar, bkz. TransactionManagementConfig'teki ayni tuzak). Sifir satir silinene kadar
 * dongude devam eder.
 */
@Component
@Profile("!migrate")
public class OutboxCleanupJob {

  private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);
  private static final Duration RETENTION = Duration.ofDays(7);
  private static final int BATCH_SIZE = 10_000;

  private final OutboxEventRepository outboxEventRepository;

  public OutboxCleanupJob(OutboxEventRepository outboxEventRepository) {
    this.outboxEventRepository = outboxEventRepository;
  }

  @Scheduled(cron = "0 0 3 * * *")
  public void cleanup() {
    Instant cutoff = Instant.now().minus(RETENTION);
    int totalDeleted = 0;
    int deletedInBatch;
    do {
      deletedInBatch = outboxEventRepository.deleteProcessedBatch(cutoff, BATCH_SIZE);
      totalDeleted += deletedInBatch;
    } while (deletedInBatch == BATCH_SIZE);
    if (totalDeleted > 0) {
      log.info("Outbox cleanup: {} islenmis kayit silindi (cutoff={}).", totalDeleted, cutoff);
    }
  }
}
