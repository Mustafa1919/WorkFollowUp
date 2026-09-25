package com.app.tracker.flow.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * V28 {@code task_aging_alerts} — {@code TaskWatcherRepository} ile AYNI desen: tekil-anahtarli
 * kucuk bir durum tablosu icin JPA entity yerine native SQL. RLS'e tabidir (FORCE), her cagri
 * servis katmaninin actigi transaction icinde, tenant GUC'u kurulmusken yapilmalidir.
 */
@Repository
public class TaskAgingAlertRepository {

  private final EntityManager entityManager;

  public TaskAgingAlertRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** Bu gorev icin en son bildirilen seviye, hic bildirilmediyse {@code 0}. */
  public int currentLevel(UUID taskId) {
    try {
      Number level =
          (Number)
              entityManager
                  .createNativeQuery("SELECT level FROM task_aging_alerts WHERE task_id = ?1")
                  .setParameter(1, taskId)
                  .getSingleResult();
      return level.intValue();
    } catch (NoResultException e) {
      return 0;
    }
  }

  public void upsertLevel(UUID taskId, UUID workspaceId, int level) {
    entityManager
        .createNativeQuery(
            "INSERT INTO task_aging_alerts (task_id, workspace_id, level, created_at) "
                + "VALUES (?1, ?2, ?3, NOW()) "
                + "ON CONFLICT (task_id) DO UPDATE SET level = ?3, created_at = NOW()")
        .setParameter(1, taskId)
        .setParameter(2, workspaceId)
        .setParameter(3, level)
        .executeUpdate();
  }

  /** Gorev artik esigin altina donduyse (veya Done oldu/silindiyse) kaydi temizler. */
  public void clear(UUID taskId) {
    entityManager
        .createNativeQuery("DELETE FROM task_aging_alerts WHERE task_id = ?1")
        .setParameter(1, taskId)
        .executeUpdate();
  }

  /**
   * Bu projede daha once uyari gonderilmis TUM gorevler ({@code tasks} ile JOIN — tablo kendisi
   * proje bilgisi tasimaz). {@code AgingWipAlertService} bunu "artik acik degil" (Done oldu) olan
   * gorevlerin kaydini temizlemek icin kullanir; {@code findOpenByProjectId} onlari zaten
   * DONDURMEDIGI icin normal dongu bu satirlari hic ziyaret etmez.
   */
  @SuppressWarnings("unchecked")
  public List<UUID> findAlertedTaskIdsForProject(UUID projectId) {
    return entityManager
        .createNativeQuery(
            "SELECT a.task_id FROM task_aging_alerts a "
                + "JOIN tasks t ON t.id = a.task_id WHERE t.project_id = ?1")
        .setParameter(1, projectId)
        .getResultList();
  }
}
