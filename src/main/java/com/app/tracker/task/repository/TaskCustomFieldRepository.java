package com.app.tracker.task.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * DATABASE_SCHEMA.md 2.7 — {@code tasks.custom_fields} JSONB kolonundaki {@code story_point}. JSONB
 * type mapping'i (Hibernate 7 + Jackson 3 uyumu belirsiz) yerine native SQL. Okuma metotlari
 * (getStoryPoint/getStoryPoints) kendi {@code @Transactional(readOnly = true)} sinirini tasir —
 * TaskController'dan (servis katmani DISINDAN) dogrudan cagrilirlar; bu olmadan TenancyGuardAspect
 * hic devreye girmez (pointcut {@code @Transactional} varligina bakar), {@code SET LOCAL
 * app.current_workspace_id} atlanir ve RLS satiri sessizce gizler (deger var ama {@code null}
 * donerdi) — Backend-Notlar 2026-09-20 "TenancyGuardAspect ve repository @Transactional" tuzaginin
 * story point icin YASANMIS hali (bu artimda testle yakalandi, bkz.
 * TaskResponseStoryPointIntegrationTest). setStoryPoint YAZMA yolu ise HALA
 * TaskService.updateStoryPoint'in ACIK transaction'ina baglidir (outbox/task_events ile AYNI
 * transaction'da olmasi gerektigi icin kendi sinirini ACMAZ).
 */
@Repository
public class TaskCustomFieldRepository {

  private final EntityManager entityManager;

  public TaskCustomFieldRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Transactional(readOnly = true)
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

  /**
   * Liste uc noktalarinda (TaskController) N+1'i onlemek icin batch okuma — TaskTagRepository
   * #findTagsForTasks ile AYNI desen: native SQL'de {@code IN (:liste)} otomatik genislemedigi icin
   * dinamik pozisyonel placeholder'lar kullanilir. Girdide olmayan/degeri NULL olan task_id sonuc
   * map'inde hic YOKTUR (caller {@code getOrDefault(id, null)} ile okumali).
   */
  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public Map<UUID, Integer> getStoryPoints(List<UUID> taskIds) {
    if (taskIds.isEmpty()) {
      return Map.of();
    }
    StringBuilder sql =
        new StringBuilder("SELECT id, custom_fields ->> 'story_point' FROM tasks WHERE id IN (");
    for (int i = 0; i < taskIds.size(); i++) {
      sql.append(i == 0 ? "?" : ",?").append(i + 1);
    }
    sql.append(")");
    Query query = entityManager.createNativeQuery(sql.toString());
    for (int i = 0; i < taskIds.size(); i++) {
      query.setParameter(i + 1, taskIds.get(i));
    }
    List<Object[]> rows = query.getResultList();
    Map<UUID, Integer> result = new LinkedHashMap<>();
    for (Object[] row : rows) {
      if (row[1] != null) {
        result.put((UUID) row[0], Integer.valueOf((String) row[1]));
      }
    }
    return result;
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
