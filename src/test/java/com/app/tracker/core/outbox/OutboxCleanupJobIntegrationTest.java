package com.app.tracker.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.app.tracker.core.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 2, madde 4 — {@code OutboxCleanupJob} veri SILEN tek Faz 2 kod
 * yolu oldugu halde hic testi yoktu. Buradaki kritik invariant: retention penceresinden eski olsa
 * bile HENUZ ISLENMEMIS ({@code processed_at IS NULL}) bir satir ASLA silinmemeli — silinirse olay
 * Kafka'ya hic gitmeden sessizce kaybolur ve Outbox Pattern'in tum garantisi cokerdi.
 */
@SpringBootTest
class OutboxCleanupJobIntegrationTest extends AbstractIntegrationTest {

  private static final String TOPIC = "cleanup.test.events";

  @Autowired private OutboxCleanupJob cleanupJob;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  void deletesOldProcessedRowsButNeverUnprocessedOnes() {
    Instant old = Instant.now().minus(30, ChronoUnit.DAYS);

    UUID oldProcessed = insertRow(old, old); // silinmeli
    UUID oldUnprocessed = insertRow(old, null); // KALMALI — veri kaybi korumasi
    UUID recentProcessed = insertRow(Instant.now(), Instant.now()); // KALMALI — retention icinde

    cleanupJob.cleanup();

    assertEquals(0, countById(oldProcessed), "7 gunden eski islenmis satir silinmeliydi");
    assertEquals(
        1,
        countById(oldUnprocessed),
        "ISLENMEMIS satir yasina bakilmaksizin ASLA silinmemeli (veri kaybi)");
    assertEquals(1, countById(recentProcessed), "Retention penceresi icindeki satir korunmali");
  }

  private UUID insertRow(Instant createdAt, Instant processedAt) {
    UUID id = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status ->
            entityManager
                .createNativeQuery(
                    "INSERT INTO outbox_events "
                        + "(id, topic, event_type, schema_version, aggregate_id, workspace_id,"
                        + " payload, created_at, processed_at) "
                        + "VALUES (?1, ?2, 'CLEANUP_TEST', 1, ?3, ?4, '{}' ::jsonb, ?5, ?6)")
                .setParameter(1, id)
                .setParameter(2, TOPIC)
                .setParameter(3, UUID.randomUUID())
                .setParameter(4, UUID.randomUUID())
                .setParameter(5, createdAt)
                .setParameter(6, processedAt)
                .executeUpdate());
    return id;
  }

  private int countById(UUID id) {
    return transactionTemplate.execute(
        status ->
            ((Number)
                    entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM outbox_events WHERE id = ?1")
                        .setParameter(1, id)
                        .getSingleResult())
                .intValue());
  }
}
