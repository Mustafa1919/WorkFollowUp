package com.app.tracker.task.controller;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.task.dto.AssignSprintRequest;
import com.app.tracker.task.dto.CreateTaskRequest;
import com.app.tracker.task.dto.TaskResponse;
import com.app.tracker.task.dto.UpdateDueDateRequest;
import com.app.tracker.task.dto.UpdateStoryPointRequest;
import com.app.tracker.task.dto.UpdateTaskStatusRequest;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PHASE_1_DETAILED_DESIGN.md Bolum 4/4.1 — task CRUD + keyset pagination. */
@RestController
public class TaskController {

  private static final int MAX_PAGE_SIZE = 200;
  private static final int MAX_CALENDAR_RANGE_DAYS = 62;

  private final TaskService taskService;

  public TaskController(TaskService taskService) {
    this.taskService = taskService;
  }

  @PostMapping("/api/v1/projects/{projectId}/tasks")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public ResponseEntity<TaskResponse> create(
      @PathVariable UUID projectId, @Valid @RequestBody CreateTaskRequest request) {
    Task task =
        taskService.createTask(projectId, request.title(), request.dueDate(), CurrentUser.id());
    return ResponseEntity.status(HttpStatus.CREATED).body(TaskResponse.from(task));
  }

  /**
   * Takvim gorunumu: {@code dueDate}'i {@code [from, to]} araliginda olan gorevler, sayfalamasiz.
   * Aralik en fazla {@value #MAX_CALENDAR_RANGE_DAYS} gun (6 haftalik ay izgarasi 42 gun).
   */
  @GetMapping("/api/v1/projects/{projectId}/tasks/calendar")
  public List<TaskResponse> calendar(
      @PathVariable UUID projectId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    if (ChronoUnit.DAYS.between(from, to) > MAX_CALENDAR_RANGE_DAYS) {
      throw new BusinessRuleException(
          "Takvim araligi en fazla " + MAX_CALENDAR_RANGE_DAYS + " gun olabilir.");
    }
    return taskService.listTasksByDueDate(projectId, from, to).stream()
        .map(TaskResponse::from)
        .toList();
  }

  /** {@code dueDate: null} gorevi takvimden kaldirir. */
  @PutMapping("/api/v1/tasks/{taskId}/due-date")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public ResponseEntity<TaskResponse> updateDueDate(
      @PathVariable UUID taskId, @RequestBody UpdateDueDateRequest request) {
    Task task = taskService.updateDueDate(taskId, request.dueDate(), CurrentUser.id());
    return ResponseEntity.ok(TaskResponse.from(task));
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
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public ResponseEntity<TaskResponse> updateStatus(
      @PathVariable UUID taskId, @Valid @RequestBody UpdateTaskStatusRequest request) {
    Task task = taskService.updateStatus(taskId, request.status(), CurrentUser.id());
    return ResponseEntity.ok(TaskResponse.from(task));
  }

  /** {@code sprintId: null} gorevi sprint'ten cikarir. */
  @PutMapping("/api/v1/tasks/{taskId}/sprint")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public ResponseEntity<TaskResponse> assignSprint(
      @PathVariable UUID taskId, @RequestBody AssignSprintRequest request) {
    Task task = taskService.assignSprint(taskId, request.sprintId(), CurrentUser.id());
    return ResponseEntity.ok(TaskResponse.from(task));
  }

  @PutMapping("/api/v1/tasks/{taskId}/story-point")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public ResponseEntity<TaskResponse> updateStoryPoint(
      @PathVariable UUID taskId, @Valid @RequestBody UpdateStoryPointRequest request) {
    Task task = taskService.updateStoryPoint(taskId, request.storyPoint(), CurrentUser.id());
    return ResponseEntity.ok(TaskResponse.from(task));
  }

  /** Tamamlananlar sayfasi: onaylanmis gorevler, onay zamanina gore yeniden eskiye. */
  @GetMapping("/api/v1/projects/{projectId}/tasks/approved")
  public PageResponse<TaskResponse> listApproved(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor) {
    int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    PageResponse<Task> page = taskService.listApprovedTasks(projectId, boundedLimit, cursor);
    List<TaskResponse> data = page.data().stream().map(TaskResponse::from).toList();
    return new PageResponse<>(data, page.nextCursor(), page.hasMore());
  }

  /** Onay yetkisi ADMIN/MANAGER: gorevi yapan DEVELOPER kendi isini onaylayamaz. */
  @PostMapping("/api/v1/tasks/{taskId}/approval")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "')")
  public ResponseEntity<TaskResponse> approve(@PathVariable UUID taskId) {
    return ResponseEntity.ok(TaskResponse.from(taskService.approve(taskId, CurrentUser.id())));
  }

  @DeleteMapping("/api/v1/tasks/{taskId}/approval")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "')")
  public ResponseEntity<TaskResponse> revokeApproval(@PathVariable UUID taskId) {
    return ResponseEntity.ok(
        TaskResponse.from(taskService.revokeApproval(taskId, CurrentUser.id())));
  }

  /** Soft delete; yalniz workspace ADMIN. */
  @DeleteMapping("/api/v1/tasks/{taskId}")
  @PreAuthorize("@securityGuard.hasCurrentWorkspaceRole('" + WorkspaceRole.ADMIN + "')")
  public ResponseEntity<Void> delete(@PathVariable UUID taskId) {
    taskService.deleteTask(taskId, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }
}
