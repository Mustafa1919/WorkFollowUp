package com.app.tracker.tag.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * {@code task_tags} cok-cok atama tablosu — TaskEventRepository/OutboxEventRepository ile ayni
 * desen: kompozit PK'li bu ara tabloya tam bir JPA entity kurmak yerine (bu artimda tek ihtiyac
 * assign/unassign/batch-okuma, iliski gezinme degil) dogrudan native SQL kullanilir.
 */
@Repository
public class TaskTagRepository {

  private final EntityManager entityManager;

  public TaskTagRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /**
   * Idempotent atama: {@code ON CONFLICT DO NOTHING} — ayni etiketi iki kez atamak hata degil
   * no-op'tur (PUT semantigiyle tutarli). Zaten atanmissa {@code false} doner.
   */
  public boolean assign(UUID taskId, UUID tagId, UUID workspaceId) {
    int rows =
        entityManager
            .createNativeQuery(
                "INSERT INTO task_tags (task_id, tag_id, workspace_id, created_at) "
                    + "VALUES (?1, ?2, ?3, NOW()) ON CONFLICT (task_id, tag_id) DO NOTHING")
            .setParameter(1, taskId)
            .setParameter(2, tagId)
            .setParameter(3, workspaceId)
            .executeUpdate();
    return rows > 0;
  }

  /** Zaten atanmamissa {@code false} doner (idempotent unassign). */
  public boolean unassign(UUID taskId, UUID tagId) {
    int rows =
        entityManager
            .createNativeQuery("DELETE FROM task_tags WHERE task_id = ?1 AND tag_id = ?2")
            .setParameter(1, taskId)
            .setParameter(2, tagId)
            .executeUpdate();
    return rows > 0;
  }

  /** Bir gorevin/gorev grubunun etiketleri; controller/service N+1'i onlemek icin BATCH cagirir. */
  public record TagRow(UUID taskId, UUID tagId, String name, String color, Instant createdAt) {}

  /**
   * IN listesi Spring Data'nin JPQL koleksiyon genislemesi olmadan, dinamik pozisyonel
   * placeholder'larla kuruluyor (native SQL'de {@code IN (:liste)} otomatik genislemez) — ayni
   * projede baska bir yerde denenmemis Hibernate 7 dizi/koleksiyon parametre baglama davranisina
   * (ANY(?) gibi) guvenmek yerine, TaskEventRepository/OutboxEventRepository'deki gibi kanitlanmis
   * duz JDBC pozisyonel parametre baglamasi tercih edildi. Sayfa boyutu ust siniri (MAX_PAGE_SIZE)
   * zaten sinirli oldugundan placeholder sayisi patlamaz.
   */
  @SuppressWarnings("unchecked")
  public List<TagRow> findTagsForTasks(List<UUID> taskIds) {
    if (taskIds.isEmpty()) {
      return List.of();
    }
    StringBuilder sql =
        new StringBuilder(
            "SELECT tt.task_id, t.id, t.name, t.color, t.created_at FROM task_tags tt "
                + "JOIN tags t ON t.id = tt.tag_id WHERE tt.task_id IN (");
    for (int i = 0; i < taskIds.size(); i++) {
      sql.append(i == 0 ? "?" : ",?").append(i + 1);
    }
    sql.append(") ORDER BY t.name");
    Query query = entityManager.createNativeQuery(sql.toString());
    for (int i = 0; i < taskIds.size(); i++) {
      query.setParameter(i + 1, taskIds.get(i));
    }
    List<Object[]> rows = query.getResultList();
    return rows.stream()
        .map(
            // row[4] (created_at) dogrudan (Instant) cast edilir — OutboxEventRepository
            // #findUnprocessedBatch ile AYNI, bu projede zaten kanitlanmis Hibernate 7 native
            // query timestamptz->Instant esleme davranisi.
            row ->
                new TagRow(
                    (UUID) row[0],
                    (UUID) row[1],
                    (String) row[2],
                    (String) row[3],
                    (Instant) row[4]))
        .toList();
  }
}
