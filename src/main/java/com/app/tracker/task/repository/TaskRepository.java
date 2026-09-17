package com.app.tracker.task.repository;

import com.app.tracker.task.model.Task;
import java.time.Instant;
import java.util.List;
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

  @Query("SELECT t FROM Task t WHERE t.projectId = :projectId ORDER BY t.createdAt DESC, t.id DESC")
  List<Task> findFirstPage(@Param("projectId") UUID projectId, Pageable pageable);

  @Query(
      "SELECT t FROM Task t WHERE t.projectId = :projectId "
          + "AND (t.createdAt < :cursorCreatedAt "
          + "OR (t.createdAt = :cursorCreatedAt AND t.id < :cursorId)) "
          + "ORDER BY t.createdAt DESC, t.id DESC")
  List<Task> findNextPage(
      @Param("projectId") UUID projectId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);
}
