package com.app.tracker.task.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.outbox.OutboxEventRepository;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.repository.SprintRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.repository.TaskCounterRepository;
import com.app.tracker.task.repository.TaskCustomFieldRepository;
import com.app.tracker.task.repository.TaskEventRepository;
import com.app.tracker.task.repository.TaskRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * PHASE_1_DETAILED_DESIGN.md Bolum 4/4.1/6 — task CRUD + keyset pagination + atomik task_number
 * uretimi. {@code projectRepository.findById} RLS'e tabidir: path'teki {@code projectId} baska bir
 * tenant'a aitse burada BULUNAMAZ (satir hic dontmez), boylece yanlis workspace_id ile task
 * olusturma yapisi olarak imkansizdir.
 */
@Service
public class TaskService {

  /**
   * Kanban durum makinesi (PHASE_1_DETAILED_DESIGN Bolum 4 ornegi) — sonraki fazlarda proje bazli
   * ozel durum akislarina genisletilebilir.
   */
  private static final Set<String> VALID_STATUSES =
      Set.of(TaskStatus.TO_DO, TaskStatus.IN_PROGRESS, TaskStatus.REVIEW, TaskStatus.DONE);

  private static final String TASK_EVENTS_TOPIC = "task.events";

  private final ProjectRepository projectRepository;
  private final TaskRepository taskRepository;
  private final TaskCounterRepository taskCounterRepository;
  private final TaskEventRepository taskEventRepository;
  private final TaskCustomFieldRepository taskCustomFieldRepository;
  private final SprintRepository sprintRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  public TaskService(
      ProjectRepository projectRepository,
      TaskRepository taskRepository,
      TaskCounterRepository taskCounterRepository,
      TaskEventRepository taskEventRepository,
      TaskCustomFieldRepository taskCustomFieldRepository,
      SprintRepository sprintRepository,
      OutboxEventRepository outboxEventRepository,
      ObjectMapper objectMapper) {
    this.projectRepository = projectRepository;
    this.taskRepository = taskRepository;
    this.taskCounterRepository = taskCounterRepository;
    this.taskEventRepository = taskEventRepository;
    this.taskCustomFieldRepository = taskCustomFieldRepository;
    this.sprintRepository = sprintRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public Task createTask(UUID projectId, String title) {
    Project project = requireProject(projectId);
    int taskNumber = taskCounterRepository.nextNumber(project.getId());
    Task task =
        Task.of(UUID.randomUUID(), project.getWorkspaceId(), project.getId(), taskNumber, title);
    Task saved = taskRepository.save(task);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("taskId", saved.getId().toString());
    payload.put("projectId", saved.getProjectId().toString());
    payload.put("taskNumber", saved.getTaskNumber());
    payload.put("title", saved.getTitle());
    payload.put("status", saved.getStatus());
    outboxEventRepository.write(
        TASK_EVENTS_TOPIC,
        "TASK_CREATED",
        saved.getId(),
        saved.getWorkspaceId(),
        objectMapper.writeValueAsString(payload));
    return saved;
  }

  @Transactional(readOnly = true)
  public PageResponse<Task> listTasks(UUID projectId, int limit, String cursor) {
    requireProject(projectId);
    Pageable pageable = PageRequest.of(0, limit + 1);
    List<Task> rows =
        (cursor == null || cursor.isBlank())
            ? taskRepository.findFirstPage(projectId, pageable)
            : queryFromCursor(projectId, cursor, pageable);

    boolean hasMore = rows.size() > limit;
    List<Task> page = hasMore ? rows.subList(0, limit) : rows;
    String nextCursor =
        hasMore
            ? new TaskCursor(
                    page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
                .encode()
            : null;
    return new PageResponse<>(page, nextCursor, hasMore);
  }

  @Transactional
  public Task updateStatus(UUID taskId, String newStatus, UUID actorId) {
    if (!VALID_STATUSES.contains(newStatus)) {
      throw new BusinessRuleException("Gecersiz durum: " + newStatus);
    }
    Task task =
        taskRepository
            .findById(taskId)
            .orElseThrow(() -> new ResourceNotFoundException("Gorev bulunamadi."));
    String oldStatus = task.getStatus();
    task.updateStatus(newStatus);
    Task saved = taskRepository.save(task);
    taskEventRepository.recordStatusChange(taskId, actorId, oldStatus, newStatus);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("taskId", saved.getId().toString());
    payload.put("projectId", saved.getProjectId().toString());
    payload.put("oldStatus", oldStatus);
    payload.put("newStatus", newStatus);
    outboxEventRepository.write(
        TASK_EVENTS_TOPIC,
        "TASK_STATUS_UPDATED",
        saved.getId(),
        saved.getWorkspaceId(),
        objectMapper.writeValueAsString(payload));
    return saved;
  }

  /**
   * Gorevi bir sprint'e atar / sprint'ten cikarir ({@code sprintId == null}). Tamamlanmis bir
   * sprint'e atama reddedilir; ama tamamlanmis sprint'ten baska yere TASIMA serbesttir (spillover).
   * Bu tasima, o sprint'in velocity'sini degistirmez: worker uyeligi {@code sprint_changed}
   * tarihcesinden sprint'in {@code completedAt} kesitine gore kurar (PHASE_3 Bolum 1.1).
   */
  @Transactional
  public Task assignSprint(UUID taskId, UUID sprintId, UUID actorId) {
    Task task = requireTask(taskId);
    if (Objects.equals(task.getSprintId(), sprintId)) {
      return task;
    }
    if (sprintId != null) {
      Sprint sprint =
          sprintRepository
              .findById(sprintId)
              .orElseThrow(() -> new ResourceNotFoundException("Sprint bulunamadi."));
      if (!sprint.getProjectId().equals(task.getProjectId())) {
        throw new BusinessRuleException("Sprint, gorevin projesine ait degil.");
      }
      if (sprint.isCompleted()) {
        throw new BusinessRuleException("Tamamlanmis bir sprint'e gorev eklenemez.");
      }
    }
    UUID oldSprintId = task.getSprintId();
    task.changeSprint(sprintId);
    Task saved = taskRepository.save(task);
    taskEventRepository.recordSprintChange(taskId, actorId, oldSprintId, sprintId);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("taskId", saved.getId().toString());
    payload.put("projectId", saved.getProjectId().toString());
    payload.put("oldSprintId", oldSprintId == null ? null : oldSprintId.toString());
    payload.put("newSprintId", sprintId == null ? null : sprintId.toString());
    writeTaskEvent("TASK_SPRINT_CHANGED", saved, payload);
    return saved;
  }

  @Transactional
  public Task updateStoryPoint(UUID taskId, Integer storyPoint, UUID actorId) {
    Task task = requireTask(taskId);
    Integer oldStoryPoint = taskCustomFieldRepository.getStoryPoint(taskId);
    if (Objects.equals(oldStoryPoint, storyPoint)) {
      return task;
    }
    taskCustomFieldRepository.setStoryPoint(taskId, storyPoint);
    taskEventRepository.recordStoryPointChange(taskId, actorId, oldStoryPoint, storyPoint);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", task.getProjectId().toString());
    payload.put("oldStoryPoint", oldStoryPoint);
    payload.put("newStoryPoint", storyPoint);
    writeTaskEvent("TASK_STORY_POINT_UPDATED", task, payload);
    return task;
  }

  private void writeTaskEvent(String eventType, Task task, Map<String, Object> payload) {
    outboxEventRepository.write(
        TASK_EVENTS_TOPIC,
        eventType,
        task.getId(),
        task.getWorkspaceId(),
        objectMapper.writeValueAsString(payload));
  }

  private Task requireTask(UUID taskId) {
    return taskRepository
        .findById(taskId)
        .orElseThrow(() -> new ResourceNotFoundException("Gorev bulunamadi."));
  }

  private List<Task> queryFromCursor(UUID projectId, String cursor, Pageable pageable) {
    TaskCursor decoded = TaskCursor.decode(cursor);
    return taskRepository.findNextPage(projectId, decoded.createdAt(), decoded.id(), pageable);
  }

  private Project requireProject(UUID projectId) {
    return projectRepository
        .findById(projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Proje bulunamadi."));
  }
}
