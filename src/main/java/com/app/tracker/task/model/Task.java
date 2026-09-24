package com.app.tracker.task.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * DATABASE_SCHEMA.md 2.7 — RLS'e tabidir (bkz. V2__add_rls_policies.sql). sprint_id Faz 3 Dilim
 * 3.0'da, parentTaskId V19'da (Subtask), assigneeId/description/createdBy V22'de maplendi.
 * custom_fields (story_point) bilerek entity'ye MAPLENMEDI: JSONB type mapping'i yerine {@code
 * TaskCustomFieldRepository} native SQL ile yonetir (TaskEventRepository ile ayni desen).
 */
@Entity
@Table(name = "tasks")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor
public class Task {

  @Id private UUID id;

  private UUID workspaceId;

  private UUID projectId;

  private UUID sprintId;

  /** V19: tek seviyeli subtask iliskisi (aynı projeyle sinirlidir, TaskService kontrol eder). */
  private UUID parentTaskId;

  private Integer taskNumber;

  private String title;

  private String status;

  /** V22: tek atanan kisi; null = atanmamis. Workspace uyeligi TaskService'te dogrulanir. */
  private UUID assigneeId;

  /** V22: Markdown aciklama (en fazla 20.000 karakter, DB CHECK); liste yanitlarina girmez. */
  private String description;

  /** V22: olusturan kullanici; V22'den once acilmis gorevlerde null. */
  private UUID createdBy;

  /** Takvim gunu (V15); null = tarihsiz. */
  private LocalDate dueDate;

  private Instant createdAt;

  /** V16: Done gorevin onay zamani; dolu = Kanban'dan kalkar, Tamamlananlar'da listelenir. */
  private Instant approvedAt;

  private UUID approvedBy;

  /** V16: soft delete (task_events FK'si fiziksel silmeyi engeller). */
  private Instant deletedAt;

  private UUID deletedBy;

  public static Task of(
      UUID id, UUID workspaceId, UUID projectId, Integer taskNumber, String title) {
    Task task = new Task();
    task.id = id;
    task.workspaceId = workspaceId;
    task.projectId = projectId;
    task.taskNumber = taskNumber;
    task.title = title;
    task.status = "To Do";
    task.createdAt = Instant.now();
    return task;
  }

  public void updateStatus(String newStatus) {
    this.status = newStatus;
  }

  public void changeSprint(UUID newSprintId) {
    this.sprintId = newSprintId;
  }

  public void changeParent(UUID newParentTaskId) {
    this.parentTaskId = newParentTaskId;
  }

  public void changeDueDate(LocalDate newDueDate) {
    this.dueDate = newDueDate;
  }

  public void changeAssignee(UUID newAssigneeId) {
    this.assigneeId = newAssigneeId;
  }

  public void changeDescription(String newDescription) {
    this.description = newDescription;
  }

  public void recordCreator(UUID creatorId) {
    this.createdBy = creatorId;
  }

  public boolean isApproved() {
    return approvedAt != null;
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  public void approve(UUID approverId, Instant at) {
    this.approvedAt = at;
    this.approvedBy = approverId;
  }

  public void revokeApproval() {
    this.approvedAt = null;
    this.approvedBy = null;
  }

  public void markDeleted(UUID actorId, Instant at) {
    this.deletedAt = at;
    this.deletedBy = actorId;
  }
}
