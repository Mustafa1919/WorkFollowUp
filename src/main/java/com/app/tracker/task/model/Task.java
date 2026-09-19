package com.app.tracker.task.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DATABASE_SCHEMA.md 2.7 — RLS'e tabidir (bkz. V2__add_rls_policies.sql). Faz 1'in bu diliminde
 * yalnizca REST API'nin ihtiyac duydugu alanlar mapleniyor; parent_task_id/assignee_id nullable
 * oldugundan DB-seviyesi bir kisitlama olusturmuyor, sonraki fazlarda eklenecek. sprint_id Faz 3
 * Dilim 3.0'da eklendi. custom_fields (story_point) bilerek entity'ye MAPLENMEDI: JSONB type
 * mapping'i yerine {@code TaskCustomFieldRepository} native SQL ile yonetir (TaskEventRepository
 * ile ayni desen).
 */
@Entity
@Table(name = "tasks")
@Getter
@NoArgsConstructor
public class Task {

  @Id private UUID id;

  private UUID workspaceId;

  private UUID projectId;

  private UUID sprintId;

  private Integer taskNumber;

  private String title;

  private String status;

  private Instant createdAt;

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
}
