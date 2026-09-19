package com.app.tracker.core.idempotency;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code processed_events} sinirsiz buyumesin diye eski kayitlari parca parca siler (bkz. {@code
 * OutboxCleanupJob}: ayni desen, transaction siniri store'da).
 *
 * <p>Retention (30 gun), Kafka topic retention'indan (varsayilan 7 gun) ve makul bir DLT replay
 * penceresinden UZUN olmak zorundadir: kayit silindikten sonra ayni olay yeniden teslim edilirse
 * "yeni" sanilip tekrar islenir. Kalan risk Cycle Time gibi durum projeksiyonlarinda ayrica {@code
 * last_event_at} korumasiyla (eski tarihli olay yok sayilir) azaltilir.
 */
@Component
@Profile("!migrate")
public class ProcessedEventCleanupJob {

  private static final Logger log = LoggerFactory.getLogger(ProcessedEventCleanupJob.class);
  private static final Duration RETENTION = Duration.ofDays(30);
  private static final int BATCH_SIZE = 10_000;

  private final ProcessedEventStore processedEventStore;

  public ProcessedEventCleanupJob(ProcessedEventStore processedEventStore) {
    this.processedEventStore = processedEventStore;
  }

  @Scheduled(cron = "0 15 3 * * *")
  public void cleanup() {
    Instant cutoff = Instant.now().minus(RETENTION);
    int total = 0;
    int deleted;
    do {
      deleted = processedEventStore.deleteOlderThan(cutoff, BATCH_SIZE);
      total += deleted;
    } while (deleted == BATCH_SIZE);
    if (total > 0) {
      log.info("processed_events cleanup: {} kayit silindi (cutoff={}).", total, cutoff);
    }
  }
}
