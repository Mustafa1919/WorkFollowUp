package com.app.tracker.search.repository;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Dalga 1.6 — V26'daki {@code search_vector} generated column'lari native SQL ile sorgular
 * (TaskEventRepository/TaskTagRepository ile ayni desen: RLS'e tabi tablolar {@code @Transactional}
 * icinde cagirildigi surece guvenli, ayrica workspace_id filtresi eklemeye gerek yok). {@code
 * ts_headline} isaretleyicileri (\u0001/\u0002) HTML DEGIL — frontend metni boler ve kendi vurgu
 * elemanini React text node'u olarak basar, boylece gorev/yorum govdesindeki kullanici metni asla
 * dangerouslySetInnerHTML'e gitmez.
 */
@Repository
public class SearchRepository {

  private static final String HEADLINE_OPTS =
      "StartSel=\u0001, StopSel=\u0002, MaxFragments=1, MaxWords=18, MinWords=4";

  private final EntityManager entityManager;

  public SearchRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public record TaskHit(
      UUID id,
      UUID projectId,
      String projectKey,
      int taskNumber,
      String title,
      String status,
      String snippet,
      double rank) {}

  public record CommentHit(
      UUID id,
      UUID taskId,
      UUID projectId,
      String projectKey,
      int taskNumber,
      String taskTitle,
      String snippet,
      double rank) {}

  @SuppressWarnings("unchecked")
  public List<TaskHit> searchTasks(String query, int limit) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT t.id, t.project_id, p.key, t.task_number, t.title, t.status, "
                    + "ts_headline('simple', coalesce(t.description, ''), q, '"
                    + HEADLINE_OPTS
                    + "'), ts_rank(t.search_vector, q)::float8 "
                    + "FROM tasks t JOIN projects p ON p.id = t.project_id, "
                    + "plainto_tsquery('simple', immutable_unaccent(?1)) q "
                    + "WHERE t.deleted_at IS NULL AND t.search_vector @@ q "
                    + "ORDER BY ts_rank(t.search_vector, q) DESC, t.created_at DESC LIMIT ?2")
            .setParameter(1, query)
            .setParameter(2, limit)
            .getResultList();
    return rows.stream().map(this::toTaskHit).toList();
  }

  @SuppressWarnings("unchecked")
  public List<CommentHit> searchComments(String query, int limit) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT c.id, c.task_id, t.project_id, p.key, t.task_number, t.title, "
                    + "ts_headline('simple', c.body, q, '"
                    + HEADLINE_OPTS
                    + "'), ts_rank(c.search_vector, q)::float8 "
                    + "FROM comments c "
                    + "JOIN tasks t ON t.id = c.task_id AND t.deleted_at IS NULL "
                    + "JOIN projects p ON p.id = t.project_id, "
                    + "plainto_tsquery('simple', immutable_unaccent(?1)) q "
                    + "WHERE c.deleted_at IS NULL AND c.search_vector @@ q "
                    + "ORDER BY ts_rank(c.search_vector, q) DESC, c.created_at DESC LIMIT ?2")
            .setParameter(1, query)
            .setParameter(2, limit)
            .getResultList();
    return rows.stream().map(this::toCommentHit).toList();
  }

  /** Proje anahtari + gorev numarasiyla dogrudan eslesme ("PRJ-12"), buyuk/kucuk harf duyarsiz. */
  @SuppressWarnings("unchecked")
  public Optional<TaskHit> findByProjectKeyAndNumber(String projectKey, int taskNumber) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT t.id, t.project_id, p.key, t.task_number, t.title, t.status "
                    + "FROM tasks t JOIN projects p ON p.id = t.project_id "
                    + "WHERE t.deleted_at IS NULL AND UPPER(p.key) = UPPER(?1) "
                    + "AND t.task_number = ?2 LIMIT 1")
            .setParameter(1, projectKey)
            .setParameter(2, taskNumber)
            .getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] row = rows.get(0);
    return Optional.of(
        new TaskHit(
            (UUID) row[0],
            (UUID) row[1],
            (String) row[2],
            (Integer) row[3],
            (String) row[4],
            (String) row[5],
            null,
            1.0));
  }

  private TaskHit toTaskHit(Object[] row) {
    return new TaskHit(
        (UUID) row[0],
        (UUID) row[1],
        (String) row[2],
        (Integer) row[3],
        (String) row[4],
        (String) row[5],
        emptyToNull((String) row[6]),
        ((Number) row[7]).doubleValue());
  }

  private CommentHit toCommentHit(Object[] row) {
    return new CommentHit(
        (UUID) row[0],
        (UUID) row[1],
        (UUID) row[2],
        (String) row[3],
        (Integer) row[4],
        (String) row[5],
        emptyToNull((String) row[6]),
        ((Number) row[7]).doubleValue());
  }

  private String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
