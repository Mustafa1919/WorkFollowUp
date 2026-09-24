package com.app.tracker.comment.repository;

import com.app.tracker.comment.model.Comment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * TaskRepository ile AYNI keyset desen, ama zaman yonu TERS: yorumlar bir sohbet gibi eskiden
 * yeniye okunur ({@code created_at ASC}), gorev listesi gibi yeniden eskiye DEGIL.
 */
public interface CommentRepository extends JpaRepository<Comment, UUID> {

  @Query("SELECT c FROM Comment c WHERE c.taskId = :taskId ORDER BY c.createdAt ASC, c.id ASC")
  List<Comment> findFirstPage(@Param("taskId") UUID taskId, Pageable pageable);

  @Query(
      "SELECT c FROM Comment c WHERE c.taskId = :taskId "
          + "AND (c.createdAt > :cursorCreatedAt "
          + "OR (c.createdAt = :cursorCreatedAt AND c.id > :cursorId)) "
          + "ORDER BY c.createdAt ASC, c.id ASC")
  List<Comment> findNextPage(
      @Param("taskId") UUID taskId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  /**
   * Liste uc noktalarinda (TaskController) N+1'i onlemek icin batch — TaskService#subtaskCounts ile
   * ayni desen. Silinmis yorumlar da SAYILIR: liste yanitinda "[silindi]" olarak hala bir satir
   * olarak gorunurler.
   */
  @Query("SELECT c.taskId, COUNT(c) FROM Comment c WHERE c.taskId IN :taskIds GROUP BY c.taskId")
  List<Object[]> countByTaskIds(@Param("taskIds") List<UUID> taskIds);
}
