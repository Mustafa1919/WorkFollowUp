package com.app.tracker.task.repository;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * DATABASE_SCHEMA.md 2.7 — {@code tasks.custom_fields} JSONB kolonundaki {@code story_point}. JSONB
 * type mapping'i (Hibernate 7 + Jackson 3 uyumu belirsiz) yerine native SQL; cagiran
 * {@code @Transactional} icinde olmalidir. {@code CAST(... AS ...)} bilerek {@code ?N::type} yerine
 * kullanilir (bkz. OutboxEventRepository.write: Hibernate 7 ordinal parametre parser tuzagi).
 */
@Repository
public class TaskCustomFieldRepository {

  private final EntityManager entityManager;

  public TaskCustomFieldRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public Integer getStoryPoint(UUID taskId) {
    List<?> rows =
        entityManager
            .createNativeQuery("SELECT custom_fields ->> 'story_point' FROM tasks WHERE id = ?1")
            .setParameter(1, taskId)
            .getResultList();
    // stream().findFirst() KULLANILMAZ: satir var ama deger NULL ise (story_point atanmamis) liste
    // [null] doner ve Optional null elemanda NPE firlatir.
    Object value = rows.isEmpty() ? null : rows.get(0);
    return value == null ? null : Integer.valueOf((String) value);
  }

  /** {@code null}: story_point anahtarini tamamen kaldirir. */
  public void setStoryPoint(UUID taskId, Integer storyPoint) {
    if (storyPoint == null) {
      entityManager
          .createNativeQuery(
              "UPDATE tasks SET custom_fields = COALESCE(custom_fields, CAST('{}' AS jsonb)) - 'story_point', "
                  + "updated_at = NOW() WHERE id = ?1")
          .setParameter(1, taskId)
          .executeUpdate();
      return;
    }
    entityManager
        .createNativeQuery(
            "UPDATE tasks SET custom_fields = jsonb_set(COALESCE(custom_fields, CAST('{}' AS jsonb)), "
                + "ARRAY['story_point'], to_jsonb(CAST(?2 AS integer))), updated_at = NOW() WHERE id = ?1")
        .setParameter(1, taskId)
        .setParameter(2, storyPoint)
        .executeUpdate();
  }
}
