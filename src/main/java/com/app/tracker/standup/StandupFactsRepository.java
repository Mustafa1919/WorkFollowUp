package com.app.tracker.standup;

import com.app.tracker.integration.IntegrationActor;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Dalga 2.3 — standup ozetinin okuma sorgulari. Hepsi kullaniciya ATANMIS gorevler uzerinden
 * calisir (izleyici/yorum degil — standup "benim isim" perspektifi). Native SQL: {@code
 * task_events}'in kendisi zaten native (TaskEventRepository), {@code task_aging_alerts}/{@code
 * task_dependencies} da ayni desende (TaskWatcherRepository ile AYNI gerekce, JPA entity yerine
 * dogrudan SQL).
 *
 * <p>Cagiran, RLS baglamini kurmus bir transaction icinde olmalidir.
 */
@Repository
public class StandupFactsRepository {

  private static final String TASK_COLUMNS = "t.id, p.key, t.task_number, t.title";

  private final EntityManager entityManager;

  public StandupFactsRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** Onceki is gunu (business-day) icinde SON Done gecisi olan, kullaniciya atanmis gorevler. */
  public List<StandupTaskRef> completedInRange(
      UUID assigneeId, Instant fromInclusive, Instant toExclusive) {
    return query(
        "SELECT "
            + TASK_COLUMNS
            + " FROM tasks t JOIN task_analytics ta ON ta.task_id = t.id "
            + "JOIN projects p ON p.id = t.project_id "
            + "WHERE t.assignee_id = ?1 AND ta.done_at >= ?2 AND ta.done_at < ?3",
        assigneeId,
        fromInclusive,
        toExclusive);
  }

  /** Onceki is gunu Done DISINDA bir duruma gecen, kullaniciya atanmis gorevler. */
  public List<StandupTaskRef> progressedInRange(
      UUID actorId, Instant fromInclusive, Instant toExclusive) {
    return query(
        "SELECT DISTINCT ON (t.id) "
            + TASK_COLUMNS
            + " FROM task_events e JOIN tasks t ON t.id = e.task_id "
            + "JOIN projects p ON p.id = t.project_id "
            + "WHERE e.actor_id = ?1 AND e.event_type = 'status_changed' "
            + "AND e.created_at >= ?2 AND e.created_at < ?3 "
            + "AND (e.new_value ->> 'status') IS DISTINCT FROM 'Done' "
            + "ORDER BY t.id, e.created_at DESC",
        actorId,
        fromInclusive,
        toExclusive);
  }

  /** Su an "In Progress" durumundaki, kullaniciya atanmis, silinmemis/onaysiz gorevler. */
  @SuppressWarnings("unchecked")
  public List<StandupTaskRef> currentlyInProgress(UUID assigneeId) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT "
                    + TASK_COLUMNS
                    + " FROM tasks t JOIN projects p ON p.id = t.project_id "
                    + "WHERE t.assignee_id = ?1 AND t.status = 'In Progress' "
                    + "AND t.deleted_at IS NULL AND t.approved_at IS NULL "
                    + "ORDER BY t.task_number")
            .setParameter(1, assigneeId)
            .getResultList();
    return toRefs(rows);
  }

  /** En az bir ACIK (Done olmayan) blocker'i olan, kullaniciya atanmis gorevler. */
  @SuppressWarnings("unchecked")
  public List<StandupTaskRef> blockedByOpenDependency(UUID assigneeId) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT DISTINCT "
                    + TASK_COLUMNS
                    + " FROM tasks t JOIN projects p ON p.id = t.project_id "
                    + "JOIN task_dependencies d ON d.blocked_task_id = t.id "
                    + "JOIN tasks blocker ON blocker.id = d.blocking_task_id "
                    + "WHERE t.assignee_id = ?1 AND t.deleted_at IS NULL AND t.approved_at IS NULL "
                    + "AND blocker.status <> 'Done' AND blocker.deleted_at IS NULL "
                    + "ORDER BY t.task_number")
            .setParameter(1, assigneeId)
            .getResultList();
    return toRefs(rows);
  }

  /** Aging WIP (Dalga 2.1) tarafindan zaten isaretlenmis, kullaniciya atanmis gorevler. */
  @SuppressWarnings("unchecked")
  public List<StandupTaskRef> aging(UUID assigneeId) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT "
                    + TASK_COLUMNS
                    + " FROM task_aging_alerts a JOIN tasks t ON t.id = a.task_id "
                    + "JOIN projects p ON p.id = t.project_id "
                    + "WHERE t.assignee_id = ?1 AND t.deleted_at IS NULL "
                    + "ORDER BY t.task_number")
            .setParameter(1, assigneeId)
            .getResultList();
    return toRefs(rows);
  }

  /**
   * Onceki is gunu webhook (GitHub) aktorunun kullaniciya atanmis bir gorevde yaptigi degisiklik.
   */
  public List<StandupTaskRef> githubActivityInRange(
      UUID assigneeId, Instant fromInclusive, Instant toExclusive) {
    return query(
        "SELECT DISTINCT ON (t.id) "
            + TASK_COLUMNS
            + " FROM task_events e JOIN tasks t ON t.id = e.task_id "
            + "JOIN projects p ON p.id = t.project_id "
            + "WHERE e.actor_id = ?1 AND t.assignee_id = ?2 "
            + "AND e.created_at >= ?3 AND e.created_at < ?4 "
            + "ORDER BY t.id, e.created_at DESC",
        IntegrationActor.SYSTEM_USER_ID,
        assigneeId,
        fromInclusive,
        toExclusive);
  }

  @SuppressWarnings("unchecked")
  private List<StandupTaskRef> query(String sql, UUID p1, Instant p2, Instant p3) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(sql)
            .setParameter(1, p1)
            .setParameter(2, p2)
            .setParameter(3, p3)
            .getResultList();
    return toRefs(rows);
  }

  @SuppressWarnings("unchecked")
  private List<StandupTaskRef> query(String sql, UUID p1, UUID p2, Instant p3, Instant p4) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(sql)
            .setParameter(1, p1)
            .setParameter(2, p2)
            .setParameter(3, p3)
            .setParameter(4, p4)
            .getResultList();
    return toRefs(rows);
  }

  private static List<StandupTaskRef> toRefs(List<Object[]> rows) {
    return rows.stream()
        .map(
            row ->
                new StandupTaskRef(
                    (UUID) row[0], (String) row[1], ((Number) row[2]).intValue(), (String) row[3]))
        .toList();
  }
}
