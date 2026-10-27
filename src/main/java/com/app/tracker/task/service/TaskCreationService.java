package com.app.tracker.task.service;

import com.app.tracker.tag.service.TagService;
import com.app.tracker.task.dto.CreateTaskRequest;
import com.app.tracker.task.model.Task;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Genisletilmis gorev olusturma (Dalga 5.4). Gorev ve istenen tum alanlar TEK transaction'da,
 * mevcut {@link TaskService}/{@link TagService} metotlari UZERINDEN yazilir (ayni olaylar, yeni
 * olay tipi yok); herhangi biri hata firlatirsa gorev dahil hicbir sey yazilmaz. Ayri servis:
 * TaskService icinde self-invocation ile aspect'in atlanmasini onler (BulkTaskService deseni).
 */
@Service
public class TaskCreationService {

  private final TaskService taskService;
  private final TagService tagService;

  public TaskCreationService(TaskService taskService, TagService tagService) {
    this.taskService = taskService;
    this.tagService = tagService;
  }

  @Transactional
  public Task create(UUID projectId, CreateTaskRequest request, UUID actorId) {
    Task task = taskService.createTask(projectId, request.title(), request.dueDate(), actorId);
    UUID taskId = task.getId();
    if (request.description() != null && !request.description().isBlank()) {
      taskService.updateDescription(taskId, request.description(), actorId);
    }
    if (request.assigneeId() != null) {
      taskService.assign(taskId, request.assigneeId(), actorId);
    }
    if (request.storyPoint() != null) {
      taskService.updateStoryPoint(taskId, request.storyPoint(), actorId);
    }
    if (request.sprintId() != null) {
      taskService.assignSprint(taskId, request.sprintId(), actorId);
    }
    for (UUID tagId : request.tagIds()) {
      tagService.assign(taskId, tagId, actorId);
    }
    return task;
  }
}
