package com.app.tracker.core.idempotency;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PHASE_3_DETAILED_DESIGN.md "Mimari Ipucu" — Kafka at-least-once teslimatina karsi ortak
 * idempotent consumer altyapisi (Faz 2 DLT Replay senaryosu ile ayni altyapi; her consumer icin
 * ayri kopya yazilmaz).
 *
 * <p>{@link #markProcessed} cagiranin ZATEN acik olan transaction'ina katilir ({@code MANDATORY}:
 * transaction yoksa hata verir) ve is verisi yazimiyla AYNI transaction'da cagrilmalidir: consumer
 * isaretledikten sonra isi yazmadan cokerse ikisi birlikte geri alinir, olay yeniden islenir
 * (exactly-once ETKISI). Isaretleme ayri bir transaction'da olsaydi, is yazilmadan "islendi"
 * damgasi kalabilir ve olay sessizce kaybolurdu.
 */
@Repository
public class ProcessedEventStore {

  private final EntityManager entityManager;

  public ProcessedEventStore(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /**
   * @return {@code true}: olay ilk kez goruluyor, islenmeli; {@code false}: daha once islenmis
   *     (yinelenen teslimat), atlanmali.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean markProcessed(String consumer, UUID eventId) {
    int inserted =
        entityManager
            .createNativeQuery(
                "INSERT INTO processed_events (consumer, event_id, processed_at) "
                    + "VALUES (?1, ?2, NOW()) ON CONFLICT (consumer, event_id) DO NOTHING")
            .setParameter(1, consumer)
            .setParameter(2, eventId)
            .executeUpdate();
    return inserted == 1;
  }

  /**
   * Yalniz OKUR, isaretlemez. Is, disari yan etki uretiyorsa ve DB transaction'ina sigmiyorsa (dis
   * HTTP cagrisi) kullanilir: "daha once yapildi mi?" bak, isi transaction DISINDA yap, basariyla
   * bitince {@link #markProcessed} ile isaretle. Bu, at-least-once semantigidir (gonderim ile
   * isaretleme arasinda cokus = tekrar gonderim); transaction icinde dis cagri beklemek ise DB
   * baglantisini cagri suresince tutardi.
   */
  @Transactional(readOnly = true)
  public boolean isProcessed(String consumer, UUID eventId) {
    Number count =
        (Number)
            entityManager
                .createNativeQuery(
                    "SELECT COUNT(*) FROM processed_events WHERE consumer = ?1 AND event_id = ?2")
                .setParameter(1, consumer)
                .setParameter(2, eventId)
                .getSingleResult();
    return count.longValue() > 0;
  }

  /** Retention temizligi icin; bkz. {@link ProcessedEventCleanupJob}. */
  @Transactional
  public int deleteOlderThan(Instant cutoff, int batchSize) {
    return entityManager
        .createNativeQuery(
            "DELETE FROM processed_events WHERE (consumer, event_id) IN "
                + "(SELECT consumer, event_id FROM processed_events "
                + "WHERE processed_at < ?1 LIMIT ?2)")
        .setParameter(1, cutoff)
        .setParameter(2, batchSize)
        .executeUpdate();
  }
}
