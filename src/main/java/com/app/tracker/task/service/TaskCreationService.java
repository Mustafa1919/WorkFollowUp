package com.app.tracker.task.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.sprint.repository.SprintRepository;
import com.app.tracker.tag.service.TagService;
import com.app.tracker.task.dto.CreateSubtaskRequest;
import com.app.tracker.task.dto.CreateTaskRequest;
import com.app.tracker.task.model.Task;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
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

  private final TaskAccessPolicy accessPolicy;
  private final SprintRepository sprintRepository;

  public TaskCreationService(
      TaskService taskService,
      TagService tagService,
      TaskAccessPolicy accessPolicy,
      SprintRepository sprintRepository) {
    this.taskService = taskService;
    this.tagService = tagService;
    this.accessPolicy = accessPolicy;
    this.sprintRepository = sprintRepository;
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

  /**
   * Sifirdan alt gorev (ADR-0021). Tek tx: herhangi bir adim hata atarsa gorev dahil hicbir sey
   * yazilmaz. Sirasi sozlesmeye gore: ust gorev, yetki, kural, atanan karari, olusturma.
   */
  @Transactional
  public Task createSubtask(UUID parentTaskId, CreateSubtaskRequest request, UUID actorId) {
    Task parent = taskService.getTask(parentTaskId);
    accessPolicy.checkCreateSubtask(parent, actorId);
    if (parent.getParentTaskId() != null) {
      throw new BusinessRuleException("Alt gorevin altina alt gorev acilamaz.");
    }
    if (parent.isApproved()) {
      throw new BusinessRuleException("Onaylanmis gorevin altina alt gorev acilamaz.");
    }
    UUID assigneeId = parent.getAssigneeId();
    if (request.assigneeId() != null) {
      boolean canPlan = accessPolicy.canPlan(actorId, parent.getWorkspaceId());
      if (!canPlan && !request.assigneeId().equals(parent.getAssigneeId())) {
        throw new AccessDeniedException(
            "Alt goreve baskasini atamak icin planlama yetkisi gerekli.");
      }
      assigneeId = request.assigneeId();
    }

    Task child =
        taskService.createTask(parent.getProjectId(), request.title(), request.dueDate(), actorId);
    UUID childId = child.getId();
    taskService.setParent(childId, parentTaskId, actorId);
    if (request.description() != null && !request.description().isBlank()) {
      taskService.updateDescription(childId, request.description(), actorId);
    }
    if (assigneeId != null) {
      taskService.assign(childId, assigneeId, actorId);
    }
    UUID parentSprintId = parent.getSprintId();
    if (parentSprintId != null
        && sprintRepository.findById(parentSprintId).filter(s -> !s.isCompleted()).isPresent()) {
      taskService.assignSprint(childId, parentSprintId, actorId);
    }
    for (UUID tagId : request.tagIds()) {
      tagService.assign(childId, tagId, actorId);
    }
    return child;
  }
}
