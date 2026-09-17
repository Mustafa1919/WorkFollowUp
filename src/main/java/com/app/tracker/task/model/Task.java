package com.app.tracker.task.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DATABASE_SCHEMA.md 2.7 — RLS'e tabidir (bkz. V2__add_rls_policies.sql). Faz 1'in bu diliminde
 * yalnizca izolasyon testini gecerli kilacak minimum alanlar mapleniyor; sprint_id/parent_task_id/
 * assignee_id/custom_fields nullable oldugundan DB-seviyesi bir kisitlama olusturmuyor —
 * task_number'in atomik sayac (task_counters) uzerinden uretimi ve kalan alanlarin eklenmesi REST
 * API calismasinin parcasi olarak sonraki adimda gelecek.
 */
@Entity
@Table(name = "tasks")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Task {

  @Id private UUID id;

  private UUID workspaceId;

  private UUID projectId;

  private Integer taskNumber;

  private String title;

  private String status;

  public static Task of(
      UUID id, UUID workspaceId, UUID projectId, Integer taskNumber, String title) {
    return new Task(id, workspaceId, projectId, taskNumber, title, "To Do");
  }
}
