package com.app.tracker.analytics.repository;

import com.app.tracker.analytics.model.SprintSnapshot;
import com.app.tracker.task.model.TaskStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 1.1, kural 2 — sprint uyeligi, durum ve story point, tasks'in
 * ANLIK degerinden degil {@code task_events} tarihcesinden {@code cutoff} anina gore yeniden
 * kurulur. Boylece sprint kapandiktan sonra yapilan tasima / durum / puan degisiklikleri gecmis
 * sprint'in metrigini degistiremez.
 *
 * <p>Kesit kurallari: her alan icin {@code created_at <= cutoff} olan SON olay gecerlidir; hic olay
 * yoksa baslangic degeri ("To Do", puan 0). Ayni {@code created_at}'e (ayni transaction'in NOW()'i)
 * sahip olaylarda {@code id DESC} yalnizca deterministik bir siralama saglar, gercek nedensellik
 * siralamasi DEGILDIR — pratikte bir gorev icin ayni transaction'da ayni alan iki kez degismez.
 *
 * <p>Cagiran, RLS baglamini kurmus aktif bir transaction icinde olmalidir (bkz. {@code
 * VelocityProjector}). {@code ?N} yer tutuculari tekrar kullanilir; {@code ::cast} yerine {@code
 * CAST} bilerek kullanilir (Hibernate 7 ordinal parametre tuzagi, bkz. Mimari.md).
 */
@Repository
public class SprintSnapshotRepository {

  private static final String SNAPSHOT_SQL =
      """
      WITH members AS (
        SELECT latest.task_id
        FROM (
          SELECT DISTINCT ON (e.task_id) e.task_id, e.new_value ->> 'sprintId' AS sprint_id
          FROM task_events e
          JOIN tasks t ON t.id = e.task_id
          WHERE t.project_id = ?1
            AND e.event_type = 'sprint_changed'
            AND e.created_at <= ?2
          ORDER BY e.task_id, e.created_at DESC, e.id DESC
        ) latest
        WHERE latest.sprint_id = ?3
      ),
      snapshot AS (
        SELECT
          COALESCE(
            (SELECT e.new_value ->> 'status'
               FROM task_events e
              WHERE e.task_id = m.task_id
                AND e.event_type = 'status_changed'
                AND e.created_at <= ?2
              ORDER BY e.created_at DESC, e.id DESC
              LIMIT 1),
            ?4) AS status,
          COALESCE(
            (SELECT CAST(e.new_value ->> 'storyPoint' AS integer)
               FROM task_events e
              WHERE e.task_id = m.task_id
                AND e.event_type = 'story_point_changed'
                AND e.created_at <= ?2
              ORDER BY e.created_at DESC, e.id DESC
              LIMIT 1),
            0) AS points
        FROM members m
      )
      SELECT COUNT(*),
             COUNT(*) FILTER (WHERE status = ?5),
             COALESCE(SUM(points), 0),
             COALESCE(SUM(points) FILTER (WHERE status = ?5), 0)
      FROM snapshot
      """;

  private final EntityManager entityManager;

  public SprintSnapshotRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public SprintSnapshot snapshotAt(UUID projectId, UUID sprintId, Instant cutoff) {
    Object[] row =
        (Object[])
            entityManager
                .createNativeQuery(SNAPSHOT_SQL)
                .setParameter(1, projectId)
                .setParameter(2, cutoff)
                .setParameter(3, sprintId.toString())
                .setParameter(4, TaskStatus.TO_DO)
                .setParameter(5, TaskStatus.DONE)
                .getSingleResult();
    return new SprintSnapshot(
        ((Number) row[0]).intValue(),
        ((Number) row[1]).intValue(),
        ((Number) row[2]).longValue(),
        ((Number) row[3]).longValue());
  }
}
