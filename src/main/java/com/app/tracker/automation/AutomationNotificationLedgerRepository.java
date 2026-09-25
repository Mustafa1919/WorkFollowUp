package com.app.tracker.automation;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * {@code automation_notifications_sent} (V31) — OVERDUE_NOTIFY icin gunluk dedup. {@code
 * task_aging_alerts} ile AYNI native-SQL desen: kucuk, tekil-anahtarli bir durum tablosu.
 */
@Repository
public class AutomationNotificationLedgerRepository {

  private final EntityManager entityManager;

  public AutomationNotificationLedgerRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /**
   * Idempotent isaretleme: bugun bu gorev icin bu sablonda daha once bildirim gittiyse {@code
   * false} doner (caller bildirim GONDERMEMELI).
   */
  public boolean markSent(String templateKey, UUID taskId, UUID workspaceId, LocalDate sentOn) {
    int rows =
        entityManager
            .createNativeQuery(
                "INSERT INTO automation_notifications_sent "
                    + "(template_key, task_id, workspace_id, sent_on) VALUES (?1, ?2, ?3, ?4) "
                    + "ON CONFLICT (template_key, task_id, sent_on) DO NOTHING")
            .setParameter(1, templateKey)
            .setParameter(2, taskId)
            .setParameter(3, workspaceId)
            .setParameter(4, sentOn)
            .executeUpdate();
    return rows > 0;
  }
}
