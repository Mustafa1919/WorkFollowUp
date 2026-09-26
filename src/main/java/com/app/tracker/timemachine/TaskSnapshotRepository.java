package com.app.tracker.timemachine;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * ADR-0017 — {@code SprintSnapshotRepository} (ADR-0015) ile AYNI "her alan icin created_at &lt;=
 * cutoff olan SON olay gecerlidir" deseni, ama tek sprint'e degil PROJENIN TUM gorevlerine
 * genellenmis. {@code tasks.deleted_at} zaten gercek bir zaman damgasi oldugu icin (soft delete),
 * silinme durumu icin {@code task_events}'teki {@code deleted} olayina degil DOGRUDAN bu kolona
 * bakilir — daha basit ve tek dogruluk kaynagi.
 *
 * <p>Bilinen sinir: {@code title}/{@code taskNumber} icin ayri bir "*_changed" olay tipi yok, bu
 * yuzden ANLIK (guncel) degerleri donulur — gecmis kesitte gorevin o anki basligi degil suanki
 * basligi gorunur (ADR-0017'de belgelendi, description/tag icerigi tarihceye yazilmamasiyla AYNI
 * turden bilincli bir sinirlama).
 */
@Repository
public class TaskSnapshotRepository {

  private static final String BOARD_AT_SQL =
      """
      WITH candidates AS (
        SELECT t.id, t.task_number, t.title
        FROM tasks t
        WHERE t.project_id = ?1
          AND t.created_at <= ?2
          AND (t.deleted_at IS NULL OR t.deleted_at > ?2)
      ),
      status_state AS (
        SELECT DISTINCT ON (e.task_id) e.task_id, e.new_value ->> 'status' AS status
        FROM task_events e
        JOIN candidates c ON c.id = e.task_id
        WHERE e.event_type = 'status_changed' AND e.created_at <= ?2
        ORDER BY e.task_id, e.created_at DESC, e.id DESC
      ),
      sprint_state AS (
        SELECT DISTINCT ON (e.task_id) e.task_id, e.new_value ->> 'sprintId' AS sprint_id
        FROM task_events e
        JOIN candidates c ON c.id = e.task_id
        WHERE e.event_type = 'sprint_changed' AND e.created_at <= ?2
        ORDER BY e.task_id, e.created_at DESC, e.id DESC
      ),
      points_state AS (
        SELECT DISTINCT ON (e.task_id) e.task_id,
               CAST(e.new_value ->> 'storyPoint' AS integer) AS points
        FROM task_events e
        JOIN candidates c ON c.id = e.task_id
        WHERE e.event_type = 'story_point_changed' AND e.created_at <= ?2
        ORDER BY e.task_id, e.created_at DESC, e.id DESC
      ),
      assignee_state AS (
        SELECT DISTINCT ON (e.task_id) e.task_id, e.new_value ->> 'assigneeId' AS assignee_id
        FROM task_events e
        JOIN candidates c ON c.id = e.task_id
        WHERE e.event_type = 'assignee_changed' AND e.created_at <= ?2
        ORDER BY e.task_id, e.created_at DESC, e.id DESC
      ),
      due_state AS (
        SELECT DISTINCT ON (e.task_id) e.task_id, e.new_value ->> 'dueDate' AS due_date
        FROM task_events e
        JOIN candidates c ON c.id = e.task_id
        WHERE e.event_type = 'due_date_changed' AND e.created_at <= ?2
        ORDER BY e.task_id, e.created_at DESC, e.id DESC
      )
      SELECT c.id,
             c.task_number,
             c.title,
             COALESCE(s.status, ?3),
             CAST(sp.sprint_id AS uuid),
             pt.points,
             CAST(a.assignee_id AS uuid),
             CAST(d.due_date AS date)
      FROM candidates c
      LEFT JOIN status_state s ON s.task_id = c.id
      LEFT JOIN sprint_state sp ON sp.task_id = c.id
      LEFT JOIN points_state pt ON pt.task_id = c.id
      LEFT JOIN assignee_state a ON a.task_id = c.id
      LEFT JOIN due_state d ON d.task_id = c.id
      ORDER BY c.task_number
      """;

  private final EntityManager entityManager;

  public TaskSnapshotRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public record Row(
      UUID id,
      int taskNumber,
      String title,
      String status,
      UUID sprintId,
      Integer storyPoint,
      UUID assigneeId,
      LocalDate dueDate) {}

  @SuppressWarnings("unchecked")
  public List<Row> boardAt(UUID projectId, Instant cutoff) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(BOARD_AT_SQL)
            .setParameter(1, projectId)
            .setParameter(2, cutoff)
            .setParameter(3, com.app.tracker.task.model.TaskStatus.TO_DO)
            .getResultList();
    return rows.stream().map(TaskSnapshotRepository::toRow).toList();
  }

  private static Row toRow(Object[] row) {
    return new Row(
        (UUID) row[0],
        ((Number) row[1]).intValue(),
        (String) row[2],
        (String) row[3],
        (UUID) row[4],
        row[5] == null ? null : ((Number) row[5]).intValue(),
        (UUID) row[6],
        (LocalDate) row[7]);
  }
}
