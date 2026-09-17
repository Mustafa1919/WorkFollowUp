package com.app.tracker.task.controller;

import com.app.tracker.core.web.PageResponse;
import com.app.tracker.task.dto.CreateTaskRequest;
import com.app.tracker.task.dto.TaskResponse;
import com.app.tracker.task.dto.UpdateTaskStatusRequest;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PHASE_1_DETAILED_DESIGN.md Bolum 4/4.1 — task CRUD + keyset pagination. */
@RestController
public class TaskController {

  private static final int MAX_PAGE_SIZE = 200;

  private final TaskService taskService;

  public TaskController(TaskService taskService) {
    this.taskService = taskService;
  }

  @PostMapping("/api/v1/projects/{projectId}/tasks")
  @PreAuthorize("@securityGuard.hasCurrentWorkspaceRole('WORKSPACE_ADMIN', 'MANAGER', 'DEVELOPER')")
  public ResponseEntity<TaskResponse> create(
      @PathVariable UUID projectId, @Valid @RequestBody CreateTaskRequest request) {
    Task task = taskService.createTask(projectId, request.title());
    return ResponseEntity.status(HttpStatus.CREATED).body(TaskResponse.from(task));
  }

  @GetMapping("/api/v1/projects/{projectId}/tasks")
  public PageResponse<TaskResponse> list(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor) {
    int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    PageResponse<Task> page = taskService.listTasks(projectId, boundedLimit, cursor);
    List<TaskResponse> data = page.data().stream().map(TaskResponse::from).toList();
    return new PageResponse<>(data, page.nextCursor(), page.hasMore());
  }

  @PatchMapping("/api/v1/tasks/{taskId}")
  @PreAuthorize("@securityGuard.hasCurrentWorkspaceRole('WORKSPACE_ADMIN', 'MANAGER', 'DEVELOPER')")
  public ResponseEntity<TaskResponse> updateStatus(
      @PathVariable UUID taskId, @Valid @RequestBody UpdateTaskStatusRequest request) {
    Task task = taskService.updateStatus(taskId, request.status());
    return ResponseEntity.ok(TaskResponse.from(task));
  }
}
