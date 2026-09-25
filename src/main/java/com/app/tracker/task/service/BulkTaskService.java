package com.app.tracker.task.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.tag.service.TagService;
import com.app.tracker.task.dto.BulkOperation;
import com.app.tracker.task.model.Task;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dalga 1.7 — Kanban coklu-secim toplu islem. Her gorev {@link TaskService}/{@link TagService}
 * UZERINDEN, tek bir transaction icinde islenir: boylece olay zinciri (task_events/outbox) hicbir
 * gorev icin atlanmaz ve ya hepsi uygulanir ya hicbiri (bir gorevde hata cikarsa TUMU geri alinir —
 * kismi uygulama YOK). En fazla 100 gorev sinirini {@code BulkTaskRequest} DTO'su tasir.
 */
@Service
public class BulkTaskService {

  private final TaskService taskService;
  private final TagService tagService;

  public BulkTaskService(TaskService taskService, TagService tagService) {
    this.taskService = taskService;
    this.tagService = tagService;
  }

  @Transactional
  public List<Task> apply(
      List<UUID> taskIds,
      BulkOperation operation,
      String status,
      UUID sprintId,
      UUID assigneeId,
      UUID tagId,
      UUID actorId) {
    requireOperationFields(operation, status, tagId);
    for (UUID taskId : taskIds) {
      switch (operation) {
        case STATUS -> taskService.updateStatus(taskId, status, actorId);
        case SPRINT -> taskService.assignSprint(taskId, sprintId, actorId);
        case ASSIGNEE -> taskService.assign(taskId, assigneeId, actorId);
        case ADD_TAG -> tagService.assign(taskId, tagId, actorId);
        case REMOVE_TAG -> tagService.unassign(taskId, tagId, actorId);
      }
    }
    return taskService.findByIds(taskIds);
  }

  private static void requireOperationFields(BulkOperation operation, String status, UUID tagId) {
    switch (operation) {
      case STATUS -> {
        if (status == null || status.isBlank()) {
          throw new BusinessRuleException("STATUS islemi icin 'status' gerekli.");
        }
      }
      case ADD_TAG, REMOVE_TAG -> {
        if (tagId == null) {
          throw new BusinessRuleException("Etiket islemi icin 'tagId' gerekli.");
        }
      }
      default -> {
        // SPRINT/ASSIGNEE: null gecerli bir degerdir (bkz. sinif javadoc'u).
      }
    }
  }
}
