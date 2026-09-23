package com.app.tracker.task.repository;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * DATABASE_SCHEMA.md 2.8 — task_events append-only audit tablosu. Tam bir JPA entity + JSONB type
 * converter kurmak yerine (bu artimda okuma ihtiyaci yok, sadece Faz3 analitik icin biriktirme var)
 * dogrudan native INSERT ile yaziliyor — TaskCounterRepository ile ayni desen.
 *
 * <p>Event tipleri ({@code event_type}): {@code status_changed}, {@code sprint_changed}, {@code
 * story_point_changed}, {@code due_date_changed}. Analitik Worker (Faz 3) sprint uyeligini ve story
 * point'i bu tarihceden yeniden kurar, bu yuzden ilgili her degisiklik BURAYA da yazilmak
 * zorundadir.
 */
@Repository
public class TaskEventRepository {

  private final EntityManager entityManager;
  private final ObjectMapper objectMapper;

  public TaskEventRepository(EntityManager entityManager, ObjectMapper objectMapper) {
    this.entityManager = entityManager;
    this.objectMapper = objectMapper;
  }

  public void recordStatusChange(UUID taskId, UUID actorId, String oldStatus, String newStatus) {
    record(taskId, actorId, "status_changed", "status", oldStatus, newStatus);
  }

  /** {@code null} deger gecerlidir (backlog'a alma / sprint'ten cikarma). */
  public void recordSprintChange(UUID taskId, UUID actorId, UUID oldSprintId, UUID newSprintId) {
    record(
        taskId,
        actorId,
        "sprint_changed",
        "sprintId",
        oldSprintId == null ? null : oldSprintId.toString(),
        newSprintId == null ? null : newSprintId.toString());
  }

  public void recordStoryPointChange(
      UUID taskId, UUID actorId, Integer oldStoryPoint, Integer newStoryPoint) {
    record(taskId, actorId, "story_point_changed", "storyPoint", oldStoryPoint, newStoryPoint);
  }

  /** Onay verildi ({@code approved=true}) ya da geri alindi ({@code false}). */
  public void recordApprovalChange(UUID taskId, UUID actorId, boolean approved) {
    record(taskId, actorId, "approval_changed", "approved", !approved, approved);
  }

  /** Soft delete; satir tasks'ta kalir, tarihce korunur. */
  public void recordDeletion(UUID taskId, UUID actorId) {
    record(taskId, actorId, "deleted", "deleted", false, true);
  }

  /** {@code null} deger gecerlidir (gorevi takvimden kaldirma). ISO tarih (yyyy-MM-dd) yazilir. */
  public void recordDueDateChange(
      UUID taskId, UUID actorId, LocalDate oldDueDate, LocalDate newDueDate) {
    record(
        taskId,
        actorId,
        "due_date_changed",
        "dueDate",
        oldDueDate == null ? null : oldDueDate.toString(),
        newDueDate == null ? null : newDueDate.toString());
  }

  /**
   * V10'daki SECURITY DEFINER fonksiyonu cagirir; olusturulan partition sayisini doner. DDL'i
   * app_runtime degil fonksiyonun sahibi (migration kullanicisi) calistirir. {@code CAST} bilerek
   * {@code ?N::date} yerine kullanilir (Hibernate 7 ordinal parametre tuzagi).
   */
  @Transactional
  public int ensurePartitions(LocalDate fromMonth, LocalDate toMonth) {
    Object created =
        entityManager
            .createNativeQuery(
                "SELECT ensure_task_events_partitions(CAST(?1 AS date), CAST(?2 AS date))")
            .setParameter(1, fromMonth)
            .setParameter(2, toMonth)
            .getSingleResult();
    return ((Number) created).intValue();
  }

  private void record(
      UUID taskId, UUID actorId, String eventType, String field, Object oldValue, Object newValue) {
    entityManager
        .createNativeQuery(
            "INSERT INTO task_events (id, task_id, actor_id, event_type, old_value, new_value, created_at) "
                // "?N ::jsonb" (araya BOSLUK) — bkz. OutboxEventRepository.write javadoc'u: bitisik
                // "?N::" Hibernate 7'de "Ordinal parameter label was not an integer" ile patliyor.
                + "VALUES (?1, ?2, ?3, ?4, ?5 ::jsonb, ?6 ::jsonb, NOW())")
        .setParameter(1, UUID.randomUUID())
        .setParameter(2, taskId)
        .setParameter(3, actorId)
        .setParameter(4, eventType)
        .setParameter(5, objectMapper.writeValueAsString(single(field, oldValue)))
        .setParameter(6, objectMapper.writeValueAsString(single(field, newValue)))
        .executeUpdate();
  }

  // Map.of null degeri kabul etmez; "sprintId": null gibi acik null'lar tarihcede ANLAMLIDIR.
  private static Map<String, Object> single(String field, Object value) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(field, value);
    return map;
  }
}
