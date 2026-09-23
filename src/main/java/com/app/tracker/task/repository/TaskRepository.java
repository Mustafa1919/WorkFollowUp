package com.app.tracker.task.repository;

import com.app.tracker.task.model.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * PHASE_1_DETAILED_DESIGN.md Bolum 4.1 — Keyset (Cursor) Pagination. Tuple karsilastirmasi {@code
 * (created_at, id) < (:cursorCreatedAt, :cursorId)} tum JPA saglayicilarinda portable olmadigi
 * icin, esdeger lexicographic OR/AND ifadesiyle yazildi.
 */
public interface TaskRepository extends JpaRepository<Task, UUID> {

  /** {@code uq_tasks_project_number} (project_id, task_number) essizligine dayanir. */
  Optional<Task> findByProjectIdAndTaskNumber(UUID projectId, Integer taskNumber);

  /** Takvim gorunumu: {@code [from, to]} kapali aralik, {@code idx_tasks_project_due_date}. */
  @Query(
      "SELECT t FROM Task t WHERE t.projectId = :projectId "
          + "AND t.dueDate BETWEEN :from AND :to ORDER BY t.dueDate, t.taskNumber")
  List<Task> findByDueDateRange(
      @Param("projectId") UUID projectId, @Param("from") LocalDate from, @Param("to") LocalDate to);

  @Query(
      "SELECT t FROM Task t WHERE t.projectId = :projectId AND t.approvedAt IS NULL "
          + "ORDER BY t.createdAt DESC, t.id DESC")
  List<Task> findFirstPage(@Param("projectId") UUID projectId, Pageable pageable);

  @Query(
      "SELECT t FROM Task t WHERE t.projectId = :projectId AND t.approvedAt IS NULL "
          + "AND (t.createdAt < :cursorCreatedAt "
          + "OR (t.createdAt = :cursorCreatedAt AND t.id < :cursorId)) "
          + "ORDER BY t.createdAt DESC, t.id DESC")
  List<Task> findNextPage(
      @Param("projectId") UUID projectId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  /** Tamamlananlar sayfasi: onaylanmis gorevler, onay zamanina gore yeniden eskiye. */
  @Query(
      "SELECT t FROM Task t WHERE t.projectId = :projectId AND t.approvedAt IS NOT NULL "
          + "ORDER BY t.approvedAt DESC, t.id DESC")
  List<Task> findFirstApprovedPage(@Param("projectId") UUID projectId, Pageable pageable);

  @Query(
      "SELECT t FROM Task t WHERE t.projectId = :projectId AND t.approvedAt IS NOT NULL "
          + "AND (t.approvedAt < :cursorApprovedAt "
          + "OR (t.approvedAt = :cursorApprovedAt AND t.id < :cursorId)) "
          + "ORDER BY t.approvedAt DESC, t.id DESC")
  List<Task> findNextApprovedPage(
      @Param("projectId") UUID projectId,
      @Param("cursorApprovedAt") Instant cursorApprovedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  /**
   * Bir gorevin alt gorevleri (V19, tek seviye — donen satirlarin kendi parentTaskId'si NULL'dir).
   */
  List<Task> findByParentTaskIdOrderByTaskNumber(UUID parentTaskId);

  /**
   * Liste uc noktalarinda N+1'i onlemek icin batch: her parent icin (toplam, Done sayisi).
   * TaskTagRepository#findTagsForTasks ile ayni sebeple native degil JPQL yeterli (dinamik IN
   * genisletmesi gerekmiyor, tek sorguda tum sonuc GROUP BY ile geliyor).
   */
  @Query(
      "SELECT t.parentTaskId, COUNT(t), "
          + "SUM(CASE WHEN t.status = 'Done' THEN 1L ELSE 0L END) "
          + "FROM Task t WHERE t.parentTaskId IN :parentTaskIds GROUP BY t.parentTaskId")
  List<Object[]> countChildrenByParentTaskIds(@Param("parentTaskIds") List<UUID> parentTaskIds);
}
