package com.app.tracker.retro;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Dalga 2.4 — retro sayfasinin okuma sorgulari (task_analytics/task_dependencies uzerinden, hepsi
 * verilen {@code taskIds} kumesiyle SINIRLI — sprint'e ait olmayan gorevler asla goz onune
 * alinmaz). Cagiran, RLS baglamini kurmus bir transaction icinde olmalidir.
 */
@Repository
public class RetroRepository {

  private static final String TASK_COLUMNS = "t.id, p.key, t.task_number, t.title";

  private final EntityManager entityManager;

  public RetroRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public List<RetroTaskRef> taskRefs(List<UUID> taskIds) {
    if (taskIds.isEmpty()) {
      return List.of();
    }
    return refQuery(
        "SELECT "
            + TASK_COLUMNS
            + " FROM tasks t JOIN projects p ON p.id = t.project_id "
            + "WHERE t.id IN ("
            + placeholders(taskIds.size())
            + ") ORDER BY t.task_number",
        taskIds);
  }

  /** Verilen gorevlerden HALA "Done" olmayanlar (retro'nun spillover listesi, ANLIK durum). */
  public List<RetroTaskRef> notDone(List<UUID> taskIds) {
    if (taskIds.isEmpty()) {
      return List.of();
    }
    return refQuery(
        "SELECT "
            + TASK_COLUMNS
            + " FROM tasks t JOIN projects p ON p.id = t.project_id "
            + "WHERE t.status <> 'Done' AND t.id IN ("
            + placeholders(taskIds.size())
            + ") ORDER BY t.task_number",
        taskIds);
  }

  /**
   * En uzun cycle time'a sahip {@code limit} gorev (tanimli olanlar arasinda), en yavastan basa.
   */
  @SuppressWarnings("unchecked")
  public List<CycleTimeOutlier> cycleTimeOutliers(List<UUID> taskIds, int limit) {
    if (taskIds.isEmpty()) {
      return List.of();
    }
    Query query =
        entityManager.createNativeQuery(
            "SELECT "
                + TASK_COLUMNS
                + ", ta.cycle_time_seconds FROM task_analytics ta "
                + "JOIN tasks t ON t.id = ta.task_id JOIN projects p ON p.id = t.project_id "
                + "WHERE ta.cycle_time_seconds IS NOT NULL AND ta.task_id IN ("
                + placeholders(taskIds.size())
                + ") ORDER BY ta.cycle_time_seconds DESC LIMIT "
                + limit);
    bindTaskIds(query, taskIds);
    List<Object[]> rows = query.getResultList();
    return rows.stream()
        .map(
            row ->
                new CycleTimeOutlier(
                    new RetroTaskRef(
                        (UUID) row[0],
                        (String) row[1],
                        ((Number) row[2]).intValue(),
                        (String) row[3]),
                    ((Number) row[4]).longValue()))
        .toList();
  }

  /** Bu gorevlerden hala ACIK bir blocker'i olan en ESKI bagimlilik (uzun suredir bekleyen). */
  @SuppressWarnings("unchecked")
  public Optional<LongestOpenBlocker> longestOpenBlocker(List<UUID> taskIds) {
    if (taskIds.isEmpty()) {
      return Optional.empty();
    }
    Query query =
        entityManager.createNativeQuery(
            "SELECT blocked.id, bp.key, blocked.task_number, blocked.title, "
                + "blocker.id, blp.key, blocker.task_number, blocker.title, d.created_at "
                + "FROM task_dependencies d "
                + "JOIN tasks blocked ON blocked.id = d.blocked_task_id "
                + "JOIN projects bp ON bp.id = blocked.project_id "
                + "JOIN tasks blocker ON blocker.id = d.blocking_task_id "
                + "JOIN projects blp ON blp.id = blocker.project_id "
                + "WHERE blocker.status <> 'Done' AND blocker.deleted_at IS NULL "
                + "AND d.blocked_task_id IN ("
                + placeholders(taskIds.size())
                + ") ORDER BY d.created_at ASC LIMIT 1");
    bindTaskIds(query, taskIds);
    List<Object[]> rows = query.getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] row = rows.get(0);
    return Optional.of(
        new LongestOpenBlocker(
            new RetroTaskRef(
                (UUID) row[0], (String) row[1], ((Number) row[2]).intValue(), (String) row[3]),
            new RetroTaskRef(
                (UUID) row[4], (String) row[5], ((Number) row[6]).intValue(), (String) row[7]),
            (Instant) row[8]));
  }

  @SuppressWarnings("unchecked")
  private List<RetroTaskRef> refQuery(String sql, List<UUID> taskIds) {
    Query query = entityManager.createNativeQuery(sql);
    bindTaskIds(query, taskIds);
    List<Object[]> rows = query.getResultList();
    return rows.stream()
        .map(
            row ->
                new RetroTaskRef(
                    (UUID) row[0], (String) row[1], ((Number) row[2]).intValue(), (String) row[3]))
        .toList();
  }

  private static void bindTaskIds(Query query, List<UUID> taskIds) {
    for (int i = 0; i < taskIds.size(); i++) {
      query.setParameter(i + 1, taskIds.get(i));
    }
  }

  private static String placeholders(int count) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < count; i++) {
      sb.append(i == 0 ? "?1" : ",?" + (i + 1));
    }
    return sb.toString();
  }

  public record CycleTimeOutlier(RetroTaskRef task, long cycleTimeSeconds) {}

  public record LongestOpenBlocker(
      RetroTaskRef blockedTask, RetroTaskRef blockingTask, Instant openSince) {}
}
