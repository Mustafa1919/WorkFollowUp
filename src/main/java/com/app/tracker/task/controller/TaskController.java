package com.app.tracker.task.controller;

import com.app.tracker.comment.service.CommentService;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.dependency.service.TaskDependencyService;
import com.app.tracker.dependency.service.TaskDependencyService.DependencySummary;
import com.app.tracker.tag.dto.TagResponse;
import com.app.tracker.tag.service.TagService;
import com.app.tracker.task.dto.AssignSprintRequest;
import com.app.tracker.task.dto.CreateTaskRequest;
import com.app.tracker.task.dto.TaskActivityResponse;
import com.app.tracker.task.dto.TaskDetailResponse;
import com.app.tracker.task.dto.TaskResponse;
import com.app.tracker.task.dto.UpdateAssigneeRequest;
import com.app.tracker.task.dto.UpdateDescriptionRequest;
import com.app.tracker.task.dto.UpdateDueDateRequest;
import com.app.tracker.task.dto.UpdateStoryPointRequest;
import com.app.tracker.task.dto.UpdateTaskStatusRequest;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskCustomFieldRepository;
import com.app.tracker.task.service.TaskActivityService;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
  private final TagService tagService;
  private final TaskDependencyService taskDependencyService;
  private final TaskCustomFieldRepository taskCustomFieldRepository;
  private final CommentService commentService;
  private final TaskActivityService taskActivityService;

  public TaskController(
      TaskService taskService,
      TagService tagService,
      TaskDependencyService taskDependencyService,
      TaskCustomFieldRepository taskCustomFieldRepository,
      CommentService commentService,
      TaskActivityService taskActivityService) {
    this.taskService = taskService;
    this.tagService = tagService;
    this.taskDependencyService = taskDependencyService;
    this.taskCustomFieldRepository = taskCustomFieldRepository;
    this.commentService = commentService;
    this.taskActivityService = taskActivityService;
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
    // Yeni gorevin hicbir etiketi/story point'i/alt gorevi/bagimliligi olamaz: DB'ye sorgu atmadan
    // sabit deger (kucuk optimizasyon).
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(TaskResponse.from(task, List.of(), null, 0, 0, DependencySummary.empty(), 0));
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
    return toResponses(taskService.listTasksByDueDate(projectId, from, to));
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
    return ResponseEntity.ok(toResponse(task));
  }

  @GetMapping("/api/v1/projects/{projectId}/tasks")
  public PageResponse<TaskResponse> list(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor) {
    int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    PageResponse<Task> page = taskService.listTasks(projectId, boundedLimit, cursor);
    return new PageResponse<>(toResponses(page.data()), page.nextCursor(), page.hasMore());
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
    return ResponseEntity.ok(toResponse(task));
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
    return ResponseEntity.ok(toResponse(task));
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
    return ResponseEntity.ok(toResponse(task));
  }

  /** Tamamlananlar sayfasi: onaylanmis gorevler, onay zamanina gore yeniden eskiye. */
  @GetMapping("/api/v1/projects/{projectId}/tasks/approved")
  public PageResponse<TaskResponse> listApproved(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor) {
    int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    PageResponse<Task> page = taskService.listApprovedTasks(projectId, boundedLimit, cursor);
    return new PageResponse<>(toResponses(page.data()), page.nextCursor(), page.hasMore());
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
    return ResponseEntity.ok(toResponse(taskService.approve(taskId, CurrentUser.id())));
  }

  @DeleteMapping("/api/v1/tasks/{taskId}/approval")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "')")
  public ResponseEntity<TaskResponse> revokeApproval(@PathVariable UUID taskId) {
    return ResponseEntity.ok(toResponse(taskService.revokeApproval(taskId, CurrentUser.id())));
  }

  /** Soft delete; yalniz workspace ADMIN. */
  @DeleteMapping("/api/v1/tasks/{taskId}")
  @PreAuthorize("@securityGuard.hasCurrentWorkspaceRole('" + WorkspaceRole.ADMIN + "')")
  public ResponseEntity<Void> delete(@PathVariable UUID taskId) {
    taskService.deleteTask(taskId, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }

  /**
   * Etiket atama/kaldirma: yazma yetkisi diger gorev mutasyonlariyla AYNI (ADMIN/MANAGER/
   * DEVELOPER) — etiketlemek gorevi duzenlemenin bir parcasi, VIEWER'a acilmaz. Idempotent (PUT/
   * DELETE): zaten atanmis/atanmamis durum hata degil no-op'tur.
   */
  @PutMapping("/api/v1/tasks/{taskId}/tags/{tagId}")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public TaskResponse assignTag(@PathVariable UUID taskId, @PathVariable UUID tagId) {
    Task task = tagService.assign(taskId, tagId, CurrentUser.id());
    return toResponse(task);
  }

  @DeleteMapping("/api/v1/tasks/{taskId}/tags/{tagId}")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public TaskResponse unassignTag(@PathVariable UUID taskId, @PathVariable UUID tagId) {
    Task task = tagService.unassign(taskId, tagId, CurrentUser.id());
    return toResponse(task);
  }

  /**
   * V19 alt gorev iliskisi: {@code parentTaskId} path'te — DELETE icin ayri, parametresiz bir uc
   * nokta gerekir (kaldirma parent id bilmeden yapilir), bu yuzden PUT/DELETE tag deseninden farkli
   * olarak parent DELETE'i {@code /parent} altinda, id'siz.
   */
  @PutMapping("/api/v1/tasks/{taskId}/parent/{parentTaskId}")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public TaskResponse setParent(@PathVariable UUID taskId, @PathVariable UUID parentTaskId) {
    return toResponse(taskService.setParent(taskId, parentTaskId, CurrentUser.id()));
  }

  @DeleteMapping("/api/v1/tasks/{taskId}/parent")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public TaskResponse removeParent(@PathVariable UUID taskId) {
    return toResponse(taskService.removeParent(taskId, CurrentUser.id()));
  }

  /** TaskDialog'un alt gorev listesi: sadece sayi degil, tam TaskResponse listesi gerekir. */
  @GetMapping("/api/v1/tasks/{taskId}/subtasks")
  public List<TaskResponse> subtasks(@PathVariable UUID taskId) {
    return toResponses(taskService.listChildren(taskId));
  }

  /**
   * Dependency: {@code taskId}, {@code blockingTaskId} tarafindan bloklanir. Yetki tag/subtask ile
   * AYNI (ADMIN/MANAGER/DEVELOPER) — bagimlilik kurmak da gorev duzenlemenin bir parcasi.
   */
  @PutMapping("/api/v1/tasks/{taskId}/dependencies/{blockingTaskId}")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public TaskResponse linkDependency(@PathVariable UUID taskId, @PathVariable UUID blockingTaskId) {
    return toResponse(taskDependencyService.link(taskId, blockingTaskId, CurrentUser.id()));
  }

  @DeleteMapping("/api/v1/tasks/{taskId}/dependencies/{blockingTaskId}")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public TaskResponse unlinkDependency(
      @PathVariable UUID taskId, @PathVariable UUID blockingTaskId) {
    return toResponse(taskDependencyService.unlink(taskId, blockingTaskId, CurrentUser.id()));
  }

  /** V22: {@code assigneeId: null} atamayi kaldirir. Yetki diger gorev mutasyonlariyla AYNI. */
  @PutMapping("/api/v1/tasks/{taskId}/assignee")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public TaskResponse assign(
      @PathVariable UUID taskId, @RequestBody UpdateAssigneeRequest request) {
    return toResponse(taskService.assign(taskId, request.assigneeId(), CurrentUser.id()));
  }

  /** Aciklama + izleyiciler; liste yanitlari aciklamayi tasimaz. Okuma rol sinirsiz. */
  @GetMapping("/api/v1/tasks/{taskId}/detail")
  public TaskDetailResponse detail(@PathVariable UUID taskId) {
    return TaskDetailResponse.from(taskService.getDetail(taskId), CurrentUser.id());
  }

  @PutMapping("/api/v1/tasks/{taskId}/description")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public TaskDetailResponse updateDescription(
      @PathVariable UUID taskId, @Valid @RequestBody UpdateDescriptionRequest request) {
    UUID actorId = CurrentUser.id();
    taskService.updateDescription(taskId, request.description(), actorId);
    return TaskDetailResponse.from(taskService.getDetail(taskId), actorId);
  }

  /**
   * Izleme rol sinirsiz (VIEWER dahil): izlemek gorevi degistirmez, yalniz bildirim almaktir.
   * Idempotent PUT/DELETE.
   */
  @PutMapping("/api/v1/tasks/{taskId}/watch")
  public TaskDetailResponse watch(@PathVariable UUID taskId) {
    UUID userId = CurrentUser.id();
    return TaskDetailResponse.from(taskService.watch(taskId, userId), userId);
  }

  @DeleteMapping("/api/v1/tasks/{taskId}/watch")
  public TaskDetailResponse unwatch(@PathVariable UUID taskId) {
    UUID userId = CurrentUser.id();
    return TaskDetailResponse.from(taskService.unwatch(taskId, userId), userId);
  }

  /** "Benim islerim": aktif workspace'te bana atanmis, onaylanmamis gorevler (projeler arasi). */
  @GetMapping("/api/v1/me/tasks")
  public PageResponse<TaskResponse> myTasks(
      @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String cursor) {
    int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    PageResponse<Task> page = taskService.listAssignedTo(CurrentUser.id(), boundedLimit, cursor);
    return new PageResponse<>(toResponses(page.data()), page.nextCursor(), page.hasMore());
  }

  /** Dalga 1.5 — Activity sekmesi. Okuma rol sinirsiz (comments/subtasks ile AYNI desen). */
  @GetMapping("/api/v1/tasks/{taskId}/activity")
  public PageResponse<TaskActivityResponse> activity(
      @PathVariable UUID taskId,
      @RequestParam(defaultValue = "30") int limit,
      @RequestParam(required = false) String cursor) {
    int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    return taskActivityService.listActivity(taskId, boundedLimit, cursor);
  }

  private TaskResponse toResponse(Task task) {
    Map<UUID, int[]> subtaskCounts = taskService.subtaskCounts(List.of(task.getId()));
    int[] counts = subtaskCounts.getOrDefault(task.getId(), new int[] {0, 0});
    Map<UUID, Integer> commentCounts = commentService.commentCounts(List.of(task.getId()));
    return TaskResponse.from(
        task,
        tagService.tagsForTask(task.getId()),
        taskCustomFieldRepository.getStoryPoint(task.getId()),
        counts[0],
        counts[1],
        taskDependencyService.dependenciesForTask(task.getId()),
        commentCounts.getOrDefault(task.getId(), 0));
  }

  /**
   * Liste uc noktalari: N+1 yerine TEK sorguda batch (bkz. TagService#tagsForTasks,
   * TaskCustomFieldRepository#getStoryPoints, TaskService#subtaskCounts,
   * TaskDependencyService#dependenciesForTasks).
   */
  private List<TaskResponse> toResponses(List<Task> tasks) {
    List<UUID> taskIds = tasks.stream().map(Task::getId).toList();
    Map<UUID, List<TagResponse>> tagsByTask = tagService.tagsForTasks(taskIds);
    Map<UUID, Integer> storyPointByTask = taskCustomFieldRepository.getStoryPoints(taskIds);
    Map<UUID, int[]> subtaskCountsByTask = taskService.subtaskCounts(taskIds);
    Map<UUID, DependencySummary> dependenciesByTask =
        taskDependencyService.dependenciesForTasks(taskIds);
    Map<UUID, Integer> commentCountsByTask = commentService.commentCounts(taskIds);
    return tasks.stream()
        .map(
            task -> {
              int[] counts = subtaskCountsByTask.getOrDefault(task.getId(), new int[] {0, 0});
              return TaskResponse.from(
                  task,
                  tagsByTask.getOrDefault(task.getId(), List.of()),
                  storyPointByTask.get(task.getId()),
                  counts[0],
                  counts[1],
                  dependenciesByTask.getOrDefault(task.getId(), DependencySummary.empty()),
                  commentCountsByTask.getOrDefault(task.getId(), 0));
            })
        .toList();
  }
}
