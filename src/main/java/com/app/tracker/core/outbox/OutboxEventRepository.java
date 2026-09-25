package com.app.tracker.core.outbox;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 2 — {@code outbox_events} tablosuna native SQL ile yazar/okur
 * (TaskEventRepository/TaskCounterRepository ile ayni desen). {@code write}, cagiranin ZATEN acik
 * bir {@code @Transactional} icinde olmasini varsayar: outbox INSERT'i is verisini degistiren
 * INSERT/ UPDATE ile AYNI transaction'da olmalidir, aksi halde Outbox Pattern'in dual-write'i
 * onleme garantisi gecersiz kalir.
 */
@Repository
public class OutboxEventRepository {

  private final EntityManager entityManager;

  public OutboxEventRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public void write(
      String topic, String eventType, UUID aggregateId, UUID workspaceId, String payloadJson) {
    entityManager
        .createNativeQuery(
            "INSERT INTO outbox_events "
                + "(id, topic, event_type, schema_version, aggregate_id, workspace_id, payload, created_at) "
                // "?6 ::jsonb" (araya BOSLUK): Hibernate'in ordinal parametre parser'i "?N::"
                // bitisik
                // yazildiginda "Ordinal parameter label was not an integer" ile patliyor (bu
                // projede
                // gercek Postgres'e karsi ilk kez calisince ortaya cikan, TaskEventRepository'deki
                // ayni deseni de etkileyen bir Hibernate 7 parser tuzagi).
                + "VALUES (?1, ?2, ?3, 1, ?4, ?5, ?6 ::jsonb, NOW())")
        .setParameter(1, UUID.randomUUID())
        .setParameter(2, topic)
        .setParameter(3, eventType)
        .setParameter(4, aggregateId)
        .setParameter(5, workspaceId)
        .setParameter(6, payloadJson)
        .executeUpdate();
  }

  /** Satir; relay bunlari okuyup Kafka'ya gonderir, sonra {@link #markProcessed} ile isaretler. */
  public record UnprocessedRow(
      UUID id,
      String topic,
      String eventType,
      int schemaVersion,
      UUID aggregateId,
      UUID workspaceId,
      String payloadJson,
      Instant createdAt) {}

  /**
   * {@code FOR UPDATE SKIP LOCKED}: tek instance'ta bugun bir fark yaratmaz, ama ileride birden
   * fazla relay instance'i ayni satiri iki kez gondermeye calismaz (PHASE_2 Bolum "Trade-off" —
   * Debezium'a gecis oncesi dogru varsayilan).
   */
  @SuppressWarnings("unchecked")
  public List<UnprocessedRow> findUnprocessedBatch(int limit) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT id, topic, event_type, schema_version, aggregate_id, workspace_id, "
                    + "payload::text, created_at "
                    + "FROM outbox_events WHERE processed_at IS NULL "
                    + "ORDER BY created_at ASC LIMIT ?1 FOR UPDATE SKIP LOCKED")
            .setParameter(1, limit)
            .getResultList();
    return rows.stream()
        .map(
            row ->
                new UnprocessedRow(
                    (UUID) row[0],
                    (String) row[1],
                    (String) row[2],
                    ((Number) row[3]).intValue(),
                    (UUID) row[4],
                    (UUID) row[5],
                    (String) row[6],
                    (Instant) row[7]))
        .toList();
  }

  public void markProcessed(UUID id) {
    entityManager
        .createNativeQuery("UPDATE outbox_events SET processed_at = NOW() WHERE id = ?1")
        .setParameter(1, id)
        .executeUpdate();
  }

  /**
   * ADR-0010 — {@code notification.email} satirlari (ham dogrulama/sifirlama token'i tasir) 7
   * gunluk {@link #deleteProcessedBatch} donguesunu BEKLEMEZ, relay tarafindan basariyla
   * gonderildikten HEMEN SONRA silinir: DB'de ve WAL'de duz metin token'in kaldigi sure minimize
   * edilir. {@link #markProcessed} yerine kullanilir (ikisi birden gerekmez, satir zaten silinir).
   */
  public void delete(UUID id) {
    entityManager
        .createNativeQuery("DELETE FROM outbox_events WHERE id = ?1")
        .setParameter(1, id)
        .executeUpdate();
  }

  /**
   * PHASE_2_DETAILED_DESIGN.md Bolum 2, madde 4 — tek dev DELETE degil, 10.000'lik parcalar
   * halinde. Donen deger silinen satir sayisidir; caller sifir donene kadar dongude cagirir.
   */
  @Transactional
  public int deleteProcessedBatch(Instant olderThan, int batchSize) {
    return entityManager
        .createNativeQuery(
            "DELETE FROM outbox_events WHERE id IN "
                + "(SELECT id FROM outbox_events WHERE processed_at IS NOT NULL "
                + "AND processed_at < ?1 LIMIT ?2)")
        .setParameter(1, olderThan)
        .setParameter(2, batchSize)
        .executeUpdate();
  }
}
