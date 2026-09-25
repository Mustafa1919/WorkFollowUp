package com.app.tracker.standup;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * V29 {@code standup_digests} — {@code task_watchers} ile AYNI desen: kompozit anahtarli, JSONB
 * tasiyan tablo icin JPA entity yerine native SQL. RLS'e tabidir (FORCE).
 */
@Repository
public class StandupDigestRepository {

  private final EntityManager entityManager;

  public StandupDigestRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /**
   * Idempotent: (meeting, occurrence, kullanici) icin zaten bir satir varsa {@code false} doner ve
   * HICBIR SEY YAZMAZ — {@code StandupDigestJob}'in ayni occurrence'i iki kez islemesine karsi TEK
   * savunma budur (ayri bir ProcessedEventStore kaydi gerekmez).
   */
  public boolean insertIfAbsent(
      UUID workspaceId, UUID meetingId, LocalDate occurrenceDate, UUID userId, String factsJson) {
    int rows =
        entityManager
            .createNativeQuery(
                "INSERT INTO standup_digests "
                    + "(workspace_id, meeting_id, occurrence_date, user_id, facts, created_at, updated_at) "
                    + "VALUES (?1, ?2, ?3, ?4, ?5 ::jsonb, NOW(), NOW()) "
                    + "ON CONFLICT (meeting_id, occurrence_date, user_id) DO NOTHING")
            .setParameter(1, workspaceId)
            .setParameter(2, meetingId)
            .setParameter(3, occurrenceDate)
            .setParameter(4, userId)
            .setParameter(5, factsJson)
            .executeUpdate();
    return rows > 0;
  }

  /** Yalniz kendi notunu gunceller (sahiplik servis katmaninda {@code userId} ile saglanir). */
  public boolean updateNote(UUID meetingId, LocalDate occurrenceDate, UUID userId, String note) {
    int rows =
        entityManager
            .createNativeQuery(
                "UPDATE standup_digests SET note = ?4, updated_at = NOW() "
                    + "WHERE meeting_id = ?1 AND occurrence_date = ?2 AND user_id = ?3")
            .setParameter(1, meetingId)
            .setParameter(2, occurrenceDate)
            .setParameter(3, userId)
            .setParameter(4, note)
            .executeUpdate();
    return rows > 0;
  }

  @SuppressWarnings("unchecked")
  public List<DigestRow> findByMeetingAndDate(UUID meetingId, LocalDate occurrenceDate) {
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT user_id, facts::text, note, created_at FROM standup_digests "
                    + "WHERE meeting_id = ?1 AND occurrence_date = ?2 ORDER BY created_at")
            .setParameter(1, meetingId)
            .setParameter(2, occurrenceDate)
            .getResultList();
    return rows.stream()
        .map(
            row -> new DigestRow((UUID) row[0], (String) row[1], (String) row[2], (Instant) row[3]))
        .toList();
  }

  public record DigestRow(UUID userId, String factsJson, String note, Instant createdAt) {}
}
