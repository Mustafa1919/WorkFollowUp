package com.app.tracker.task.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * DATABASE_SCHEMA.md 2.8 — task_events append-only audit tablosu. Tam bir JPA entity + JSONB type
 * converter kurmak yerine dogrudan native INSERT/SELECT ile yaziliyor/okunuyor —
 * TaskCounterRepository ile ayni desen.
 *
 * <p>Event tipleri ({@code event_type}): {@code status_changed}, {@code sprint_changed}, {@code
 * story_point_changed}, {@code due_date_changed}, {@code assignee_changed}, {@code
 * description_changed} (V22), {@code comment_added} (V23), {@code tags_changed}, {@code
 * dependency_changed} (Dalga 1.5). Analitik Worker (Faz 3) sprint uyeligini ve story point'i bu
 * tarihceden yeniden kurar, bu yuzden ilgili her degisiklik BURAYA da yazilmak zorundadir.
 *
 * <p><b>Dalga 1.5 — Activity sekmesi:</b> onceki javadoc'un "bu artimda okuma ihtiyaci yok" notu
 * gecersiz; {@link #findFirstPage} / {@link #findNextPage} ile keyset sayfali okunur (V17/V19'un
 * tags/dependency icin "okuyucusu yok, tarihceye yazmaya gerek yok" gerekcesi de bu yuzden
 * kapandi). Eski (bu dilimden ONCEKI) tag/dependency degisiklikleri icin geri doldurma YAPILMADI —
 * bilinen sinir, eski tarihce bu iki tur icin bos gorunur.
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

  /** {@code null} deger gecerlidir (atamayi kaldirma). */
  public void recordAssigneeChange(
      UUID taskId, UUID actorId, UUID oldAssigneeId, UUID newAssigneeId) {
    record(
        taskId,
        actorId,
        "assignee_changed",
        "assigneeId",
        oldAssigneeId == null ? null : oldAssigneeId.toString(),
        newAssigneeId == null ? null : newAssigneeId.toString());
  }

  /**
   * Aciklamanin KENDISI tarihceye yazilmaz (20.000 karaktere kadar metin, her duzenlemede append-
   * only tabloyu sisirirdi); yalniz uzunlugu yazilir — Activity sekmesi "aciklamayi guncelledi"
   * demek icin bu kadarina ihtiyac duyar.
   */
  public void recordDescriptionChange(UUID taskId, UUID actorId, int oldLength, int newLength) {
    record(taskId, actorId, "description_changed", "length", oldLength, newLength);
  }

  /**
   * V23: yorumun kendisi (govde) tarihceye YAZILMAZ (comments tablosu zaten kalici kayittir);
   * yalniz kimlik, Activity sekmesi (Dalga 1.5) icin "X bir yorum ekledi" satirina yeter.
   */
  public void recordCommentAdded(UUID taskId, UUID actorId, UUID commentId) {
    record(taskId, actorId, "comment_added", "commentId", null, commentId.toString());
  }

  /**
   * Dalga 1.5: V17'nin "okuyucusu yok" gerekcesi Activity sekmesiyle kapandi — etiket
   * atama/kaldirma da artik tarihceye yazilir. {@code assignee_changed} ile AYNI once/sonra deseni:
   * eklemede {@code null -> tagName}, kaldirmada {@code tagName -> null}.
   */
  public void recordTagsChanged(UUID taskId, UUID actorId, String tagName, boolean added) {
    record(taskId, actorId, "tags_changed", "tag", added ? null : tagName, added ? tagName : null);
  }

  /**
   * Dalga 1.5: V19'un "okuyucusu yok" gerekcesi kapandi. Bagimlilik iki gorevi ilgilendirdigi icin
   * IKI ayri satir yazilir: bloklanan gorevde {@code blockedBy} alani (kimin tarafindan
   * bloklandigi), bloklayan gorevde {@code blocks} alani (kimi blokladigi) — boylece her iki
   * gorevin Activity sekmesi de kendi acisindan okunabilir bir satir gosterir.
   */
  public void recordDependencyChanged(
      UUID blockedTaskId, UUID blockingTaskId, UUID actorId, boolean added) {
    record(
        blockedTaskId,
        actorId,
        "dependency_changed",
        "blockedBy",
        added ? null : blockingTaskId.toString(),
        added ? blockingTaskId.toString() : null);
    record(
        blockingTaskId,
        actorId,
        "dependency_changed",
        "blocks",
        added ? null : blockedTaskId.toString(),
        added ? blockedTaskId.toString() : null);
  }

  /** Activity sekmesi satiri; field/oldValue/newValue old_value/new_value JSON'undan cikarilir. */
  public record ActivityEntry(
      UUID id,
      UUID actorId,
      String eventType,
      String field,
      Object oldValue,
      Object newValue,
      Instant createdAt) {}

  public List<ActivityEntry> findFirstPage(UUID taskId, int limit) {
    return mapActivityRows(
        entityManager
            .createNativeQuery(
                "SELECT id, actor_id, event_type, old_value::text, new_value::text, created_at "
                    + "FROM task_events WHERE task_id = ?1 ORDER BY created_at DESC, id DESC LIMIT ?2")
            .setParameter(1, taskId)
            .setParameter(2, limit)
            .getResultList());
  }

  public List<ActivityEntry> findNextPage(
      UUID taskId, Instant cursorCreatedAt, UUID cursorId, int limit) {
    return mapActivityRows(
        entityManager
            .createNativeQuery(
                "SELECT id, actor_id, event_type, old_value::text, new_value::text, created_at "
                    + "FROM task_events WHERE task_id = ?1 "
                    + "AND (created_at < ?2 OR (created_at = ?2 AND id < ?3)) "
                    + "ORDER BY created_at DESC, id DESC LIMIT ?4")
            .setParameter(1, taskId)
            .setParameter(2, cursorCreatedAt)
            .setParameter(3, cursorId)
            .setParameter(4, limit)
            .getResultList());
  }

  @SuppressWarnings("unchecked")
  private List<ActivityEntry> mapActivityRows(List<Object[]> rows) {
    return rows.stream().map(this::toActivityEntry).toList();
  }

  @SuppressWarnings("unchecked")
  private ActivityEntry toActivityEntry(Object[] row) {
    Map<String, Object> oldMap = objectMapper.readValue((String) row[3], Map.class);
    Map<String, Object> newMap = objectMapper.readValue((String) row[4], Map.class);
    Map.Entry<String, Object> newEntry = newMap.entrySet().iterator().next();
    return new ActivityEntry(
        (UUID) row[0],
        (UUID) row[1],
        (String) row[2],
        newEntry.getKey(),
        oldMap.get(newEntry.getKey()),
        newEntry.getValue(),
        (Instant) row[5]);
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
