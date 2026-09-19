package com.app.tracker.task.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.outbox.OutboxEventRepository;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskCounterRepository;
import com.app.tracker.task.repository.TaskEventRepository;
import com.app.tracker.task.repository.TaskRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
      Set.of("To Do", "In Progress", "Review", "Done");

  private static final String TASK_EVENTS_TOPIC = "task.events";

  private final ProjectRepository projectRepository;
  private final TaskRepository taskRepository;
  private final TaskCounterRepository taskCounterRepository;
  private final TaskEventRepository taskEventRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  public TaskService(
      ProjectRepository projectRepository,
      TaskRepository taskRepository,
      TaskCounterRepository taskCounterRepository,
      TaskEventRepository taskEventRepository,
      OutboxEventRepository outboxEventRepository,
      ObjectMapper objectMapper) {
    this.projectRepository = projectRepository;
    this.taskRepository = taskRepository;
    this.taskCounterRepository = taskCounterRepository;
    this.taskEventRepository = taskEventRepository;
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
