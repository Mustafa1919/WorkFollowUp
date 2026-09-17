package com.app.tracker.task.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskCounterRepository;
import com.app.tracker.task.repository.TaskRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PHASE_1_DETAILED_DESIGN.md Bolum 4/4.1/6 — task CRUD + keyset pagination + atomik task_number
 * uretimi. {@code projectRepository.findById} RLS'e tabidir: path'teki {@code projectId} baska bir
 * tenant'a aitse burada BULUNAMAZ (satir hic dontmez), boylece yanlis workspace_id ile task
 * olusturma yapisi olarak imkansizdir.
 */
@Service
public class TaskService {

  private final ProjectRepository projectRepository;
  private final TaskRepository taskRepository;
  private final TaskCounterRepository taskCounterRepository;

  public TaskService(
      ProjectRepository projectRepository,
      TaskRepository taskRepository,
      TaskCounterRepository taskCounterRepository) {
    this.projectRepository = projectRepository;
    this.taskRepository = taskRepository;
    this.taskCounterRepository = taskCounterRepository;
  }

  @Transactional
  public Task createTask(UUID projectId, String title) {
    Project project = requireProject(projectId);
    int taskNumber = taskCounterRepository.nextNumber(project.getId());
    Task task =
        Task.of(UUID.randomUUID(), project.getWorkspaceId(), project.getId(), taskNumber, title);
    return taskRepository.save(task);
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
  public Task updateStatus(UUID taskId, String newStatus) {
    Task task =
        taskRepository
            .findById(taskId)
            .orElseThrow(() -> new BusinessRuleException("Gorev bulunamadi."));
    task.updateStatus(newStatus);
    return taskRepository.save(task);
  }

  private List<Task> queryFromCursor(UUID projectId, String cursor, Pageable pageable) {
    TaskCursor decoded = TaskCursor.decode(cursor);
    return taskRepository.findNextPage(projectId, decoded.createdAt(), decoded.id(), pageable);
  }

  private Project requireProject(UUID projectId) {
    return projectRepository
        .findById(projectId)
        .orElseThrow(() -> new BusinessRuleException("Proje bulunamadi."));
  }
}
