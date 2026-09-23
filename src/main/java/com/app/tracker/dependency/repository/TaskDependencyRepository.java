package com.app.tracker.dependency.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * {@code task_dependencies} cok-cok iliski tablosu — TaskTagRepository ile AYNI desen: kompozit
 * PK'li ara tabloya tam bir JPA entity kurmak yerine dogrudan native SQL (assign/unassign/batch-
 * okuma, iliski gezinme yok).
 */
@Repository
public class TaskDependencyRepository {

  private final EntityManager entityManager;

  public TaskDependencyRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** Idempotent link: {@code ON CONFLICT DO NOTHING}. Zaten baglanmissa {@code false} doner. */
  public boolean link(UUID blockingTaskId, UUID blockedTaskId, UUID workspaceId) {
    int rows =
        entityManager
            .createNativeQuery(
                "INSERT INTO task_dependencies (blocking_task_id, blocked_task_id, workspace_id, "
                    + "created_at) VALUES (?1, ?2, ?3, NOW()) "
                    + "ON CONFLICT (blocking_task_id, blocked_task_id) DO NOTHING")
            .setParameter(1, blockingTaskId)
            .setParameter(2, blockedTaskId)
            .setParameter(3, workspaceId)
            .executeUpdate();
    return rows > 0;
  }

  /** Zaten baglanmamissa {@code false} doner (idempotent unlink). */
  public boolean unlink(UUID blockingTaskId, UUID blockedTaskId) {
    int rows =
        entityManager
            .createNativeQuery(
                "DELETE FROM task_dependencies "
                    + "WHERE blocking_task_id = ?1 AND blocked_task_id = ?2")
            .setParameter(1, blockingTaskId)
            .setParameter(2, blockedTaskId)
            .executeUpdate();
    return rows > 0;
  }

  /**
   * Ters-cift kontrolu: {@code blockedTaskId} zaten {@code blockingTaskId}'yi bloklamis mi (V19'un
   * DB seviyesinde yakalayamadigi kisit — servis katmaninda burada kapatilir).
   */
  public boolean existsReverse(UUID blockingTaskId, UUID blockedTaskId) {
    List<?> rows =
        entityManager
            .createNativeQuery(
                "SELECT 1 FROM task_dependencies "
                    + "WHERE blocking_task_id = ?1 AND blocked_task_id = ?2")
            .setParameter(1, blockedTaskId)
            .setParameter(2, blockingTaskId)
            .getResultList();
    return !rows.isEmpty();
  }

  /**
   * {@code taskId}'nin bloklamasindaki (blocking) veya bloklandigi (blocked) tum satirlar; {@code
   * direction} caller'in TaskRefResponse'u dogru listeye (blocking/blockedBy) koymasi icin.
   */
  public record DependencyRow(
      UUID taskId,
      boolean taskIsBlocker,
      UUID otherTaskId,
      int otherTaskNumber,
      String otherTitle,
      String otherStatus) {}

  /**
   * Liste uc noktalarinda N+1'i onlemek icin TEK sorguda batch — TaskTagRepository
   * #findTagsForTasks ile AYNI dinamik pozisyonel placeholder deseni (native SQL'de {@code IN
   * (:liste)} otomatik genislemez). Her verilen taskId icin hem "kimi bloklar" hem "kim tarafindan
   * bloklaniyor" satirlari TEK UNION sorgusuyla doner.
   */
  @SuppressWarnings("unchecked")
  public List<DependencyRow> findForTasks(List<UUID> taskIds) {
    if (taskIds.isEmpty()) {
      return List.of();
    }
    int n = taskIds.size();
    // IKI ayri placeholder araligi (?1..?n, ?(n+1)..?2n): ayni "?1..?n" metnini UNION'un iki
    // yarisinda da kullanmak Hibernate'te AYNI parametre olarak esler (tek bagliyici, iki yerde
    // kullanilir), ikinci yarinin setParameter(n+1..) cagrilari "etiketlenmemis parametre" hatasi
    // verirdi — bu, taskIds AYNI oldugunda (findForTasks(List.of(taskId)) gibi) ilk PR'da testle
    // yakalandi.
    String firstHalf = placeholders(1, n);
    String secondHalf = placeholders(n + 1, n);
    String sql =
        "SELECT d.blocking_task_id, true, d.blocked_task_id, t.task_number, t.title, t.status "
            + "FROM task_dependencies d JOIN tasks t ON t.id = d.blocked_task_id "
            + "WHERE d.blocking_task_id IN ("
            + firstHalf
            + ") "
            + "UNION ALL "
            + "SELECT d.blocked_task_id, false, d.blocking_task_id, t.task_number, t.title, t.status "
            + "FROM task_dependencies d JOIN tasks t ON t.id = d.blocking_task_id "
            + "WHERE d.blocked_task_id IN ("
            + secondHalf
            + ")";
    Query query = entityManager.createNativeQuery(sql);
    int position = 1;
    for (int pass = 0; pass < 2; pass++) {
      for (UUID taskId : taskIds) {
        query.setParameter(position++, taskId);
      }
    }
    List<Object[]> rows = query.getResultList();
    return rows.stream()
        .map(
            row ->
                new DependencyRow(
                    (UUID) row[0],
                    (Boolean) row[1],
                    (UUID) row[2],
                    (Integer) row[3],
                    (String) row[4],
                    (String) row[5]))
        .toList();
  }

  private static String placeholders(int startPosition, int count) {
    StringBuilder sql = new StringBuilder();
    for (int i = 0; i < count; i++) {
      sql.append(i == 0 ? "?" : ",?").append(startPosition + i);
    }
    return sql.toString();
  }
}
