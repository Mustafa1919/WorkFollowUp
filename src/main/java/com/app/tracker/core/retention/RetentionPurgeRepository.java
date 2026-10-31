package com.app.tracker.core.retention;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saklama suresi silmeleri (SEC-10). Her metot kendi kisa transaction'inda tek bir parca siler;
 * donguyu {@link RetentionPurgeJob} yurutur (self-invocation tuzagina dusmemek icin
 * {@code @Transactional} burada). {@code WHERE id IN (SELECT ... LIMIT n)} deseni eszamanli iki
 * pod'da da guvenlidir.
 */
@Repository
public class RetentionPurgeRepository {

  private final EntityManager entityManager;

  public RetentionPurgeRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Transactional
  public int deleteExpiredRefreshTokens(Instant now, int batchSize) {
    return entityManager
        .createNativeQuery(
            "DELETE FROM refresh_tokens WHERE id IN "
                + "(SELECT id FROM refresh_tokens WHERE expires_at < ?1 LIMIT ?2)")
        .setParameter(1, now)
        .setParameter(2, batchSize)
        .executeUpdate();
  }

  @Transactional
  public int deleteStaleVerificationTokens(Instant expiredBefore, int batchSize) {
    return entityManager
        .createNativeQuery(
            "DELETE FROM verification_tokens WHERE id IN "
                + "(SELECT id FROM verification_tokens WHERE used_at IS NOT NULL "
                + "OR expires_at < ?1 LIMIT ?2)")
        .setParameter(1, expiredBefore)
        .setParameter(2, batchSize)
        .executeUpdate();
  }

  @Transactional
  public int deleteUsageEventsBefore(Instant cutoff, int batchSize) {
    return entityManager
        .createNativeQuery(
            "DELETE FROM usage_events WHERE id IN "
                + "(SELECT id FROM usage_events WHERE occurred_at < ?1 LIMIT ?2)")
        .setParameter(1, cutoff)
        .setParameter(2, batchSize)
        .executeUpdate();
  }

  @Transactional
  public int deleteErrorEventsBefore(Instant cutoff, int batchSize) {
    return entityManager
        .createNativeQuery(
            "DELETE FROM error_events WHERE id IN "
                + "(SELECT id FROM error_events WHERE occurred_at < ?1 LIMIT ?2)")
        .setParameter(1, cutoff)
        .setParameter(2, batchSize)
        .executeUpdate();
  }

  @Transactional
  public int deleteUndeliveredEmailOutbox(Instant createdBefore, int batchSize) {
    return entityManager
        .createNativeQuery(
            "DELETE FROM outbox_events WHERE id IN "
                + "(SELECT id FROM outbox_events WHERE topic = 'notification.email' "
                + "AND processed_at IS NULL AND created_at < ?1 LIMIT ?2)")
        .setParameter(1, createdBefore)
        .setParameter(2, batchSize)
        .executeUpdate();
  }
}
