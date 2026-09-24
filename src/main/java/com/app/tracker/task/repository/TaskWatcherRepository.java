package com.app.tracker.task.repository;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * V22 {@code task_watchers} — TaskTagRepository ile AYNI desen: kompozit PK'li ara tablo icin JPA
 * entity yerine native SQL. RLS'e tabidir (FORCE), bu yuzden her cagri servis katmaninin actigi
 * transaction icinde, tenant GUC'u kurulmusken yapilmalidir.
 */
@Repository
public class TaskWatcherRepository {

  private final EntityManager entityManager;

  public TaskWatcherRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** Idempotent: zaten izliyorsa {@code false}. */
  public boolean watch(UUID taskId, UUID userId, UUID workspaceId) {
    int rows =
        entityManager
            .createNativeQuery(
                "INSERT INTO task_watchers (task_id, user_id, workspace_id, created_at) "
                    + "VALUES (?1, ?2, ?3, NOW()) ON CONFLICT (task_id, user_id) DO NOTHING")
            .setParameter(1, taskId)
            .setParameter(2, userId)
            .setParameter(3, workspaceId)
            .executeUpdate();
    return rows > 0;
  }

  /** Idempotent: zaten izlemiyorsa {@code false}. */
  public boolean unwatch(UUID taskId, UUID userId) {
    int rows =
        entityManager
            .createNativeQuery("DELETE FROM task_watchers WHERE task_id = ?1 AND user_id = ?2")
            .setParameter(1, taskId)
            .setParameter(2, userId)
            .executeUpdate();
    return rows > 0;
  }

  @SuppressWarnings("unchecked")
  public List<UUID> findWatcherIds(UUID taskId) {
    return entityManager
        .createNativeQuery(
            "SELECT user_id FROM task_watchers WHERE task_id = ?1 ORDER BY created_at, user_id")
        .setParameter(1, taskId)
        .getResultList();
  }
}
