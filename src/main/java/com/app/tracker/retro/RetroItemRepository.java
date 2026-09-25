package com.app.tracker.retro;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * V30 {@code retro_items} — {@code task_watchers}/{@code standup_digests} ile AYNI desen: kucuk,
 * kompozit olmayan ama sade bir tablo icin JPA entity yerine native SQL (bu tabloda gezinme
 * iliskisi yok, dogrudan CRUD yeterli).
 */
@Repository
public class RetroItemRepository {

  private final EntityManager entityManager;

  public RetroItemRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public void insert(
      UUID id, UUID workspaceId, UUID sprintId, String kind, String body, UUID authorId) {
    entityManager
        .createNativeQuery(
            "INSERT INTO retro_items (id, workspace_id, sprint_id, kind, body, author_id, created_at) "
                + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, NOW())")
        .setParameter(1, id)
        .setParameter(2, workspaceId)
        .setParameter(3, sprintId)
        .setParameter(4, kind)
        .setParameter(5, body)
        .setParameter(6, authorId)
        .executeUpdate();
  }

  @SuppressWarnings("unchecked")
  public List<Row> findBySprintId(UUID sprintId) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT id, sprint_id, kind, body, author_id, task_id, created_at FROM retro_items "
                    + "WHERE sprint_id = ?1 ORDER BY created_at")
            .setParameter(1, sprintId)
            .getResultList();
    return rows.stream().map(RetroItemRepository::toRow).toList();
  }

  @SuppressWarnings("unchecked")
  public Optional<Row> findById(UUID id) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT id, sprint_id, kind, body, author_id, task_id, created_at "
                    + "FROM retro_items WHERE id = ?1")
            .setParameter(1, id)
            .getResultList();
    return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
  }

  /** Sahiplik servis katmaninda kontrol edilir; burada YALNIZ id ile silinir. */
  public boolean delete(UUID id) {
    int rows =
        entityManager
            .createNativeQuery("DELETE FROM retro_items WHERE id = ?1")
            .setParameter(1, id)
            .executeUpdate();
    return rows > 0;
  }

  public void attachTask(UUID id, UUID taskId) {
    entityManager
        .createNativeQuery("UPDATE retro_items SET task_id = ?2 WHERE id = ?1")
        .setParameter(1, id)
        .setParameter(2, taskId)
        .executeUpdate();
  }

  private static Row toRow(Object[] row) {
    return new Row(
        (UUID) row[0],
        (UUID) row[1],
        (String) row[2],
        (String) row[3],
        (UUID) row[4],
        (UUID) row[5],
        (Instant) row[6]);
  }

  public record Row(
      UUID id,
      UUID sprintId,
      String kind,
      String body,
      UUID authorId,
      UUID taskId,
      Instant createdAt) {}
}
