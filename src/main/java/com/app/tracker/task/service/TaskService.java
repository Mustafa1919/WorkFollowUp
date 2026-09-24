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
import com.app.tracker.task.repository.TaskWatcherRepository;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
  private final TaskWatcherRepository taskWatcherRepository;
  private final WorkspaceUserRepository workspaceUserRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public TaskService(
      ProjectRepository projectRepository,
      TaskRepository taskRepository,
      TaskCounterRepository taskCounterRepository,
      TaskEventRepository taskEventRepository,
      TaskCustomFieldRepository taskCustomFieldRepository,
      SprintRepository sprintRepository,
      TaskWatcherRepository taskWatcherRepository,
      WorkspaceUserRepository workspaceUserRepository,
      OutboxEventRepository outboxEventRepository,
      ObjectMapper objectMapper,
      Clock clock) {
    this.projectRepository = projectRepository;
    this.taskRepository = taskRepository;
    this.taskCounterRepository = taskCounterRepository;
    this.taskEventRepository = taskEventRepository;
    this.taskCustomFieldRepository = taskCustomFieldRepository;
    this.sprintRepository = sprintRepository;
    this.taskWatcherRepository = taskWatcherRepository;
    this.workspaceUserRepository = workspaceUserRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public Task createTask(UUID projectId, String title) {
    return createTask(projectId, title, null, null);
  }

  /**
   * {@code dueDate} verilirse tarihceye {@code null -> dueDate} olarak {@code due_date_changed}
   * yazilir; bu yuzden {@code actorId} o durumda zorunludur.
   */
  @Transactional
  public Task createTask(UUID projectId, String title, LocalDate dueDate, UUID actorId) {
    Project project = requireProject(projectId);
    rejectPastDueDate(dueDate);
    int taskNumber = taskCounterRepository.nextNumber(project.getId());
    Task task =
        Task.of(UUID.randomUUID(), project.getWorkspaceId(), project.getId(), taskNumber, title);
    task.changeDueDate(dueDate);
    if (actorId != null) {
      task.recordCreator(actorId);
    }
    Task saved = taskRepository.save(task);
    if (actorId != null) {
      // Olusturan otomatik izleyicidir (V22): kendi actigi gorevin ilerleyisini Inbox'tan gorur.
      taskWatcherRepository.watch(saved.getId(), actorId, saved.getWorkspaceId());
    }
    if (dueDate != null) {
      taskEventRepository.recordDueDateChange(
          saved.getId(), Objects.requireNonNull(actorId, "actorId"), null, dueDate);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("taskId", saved.getId().toString());
    payload.put("projectId", saved.getProjectId().toString());
    payload.put("taskNumber", saved.getTaskNumber());
    payload.put("title", saved.getTitle());
    payload.put("status", saved.getStatus());
    payload.put("dueDate", dueDate == null ? null : dueDate.toString());
    payload.put("actorId", actorId == null ? null : actorId.toString());
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
    Task task = requireTask(taskId);
    if (task.isApproved() && !TaskStatus.DONE.equals(newStatus)) {
      throw new BusinessRuleException(
          "Onaylanmis gorevin durumu degistirilemez; once onay geri alinmali.");
    }
    String oldStatus = task.getStatus();
    task.updateStatus(newStatus);
    Task saved = taskRepository.save(task);
    taskEventRepository.recordStatusChange(taskId, actorId, oldStatus, newStatus);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("taskId", saved.getId().toString());
    payload.put("projectId", saved.getProjectId().toString());
    payload.put("oldStatus", oldStatus);
    payload.put("newStatus", newStatus);
    payload.put("actorId", actorId == null ? null : actorId.toString());
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
    payload.put("actorId", actorId == null ? null : actorId.toString());
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
    payload.put("actorId", actorId == null ? null : actorId.toString());
    writeTaskEvent("TASK_STORY_POINT_UPDATED", task, payload);
    return task;
  }

  @Transactional
  public Task updateDueDate(UUID taskId, LocalDate dueDate, UUID actorId) {
    Task task = requireTask(taskId);
    LocalDate oldDueDate = task.getDueDate();
    if (Objects.equals(oldDueDate, dueDate)) {
      return task;
    }
    rejectPastDueDate(dueDate);
    if (task.isApproved()) {
      throw new BusinessRuleException("Onaylanmis gorevin tarihi degistirilemez.");
    }
    task.changeDueDate(dueDate);
    Task saved = taskRepository.save(task);
    taskEventRepository.recordDueDateChange(taskId, actorId, oldDueDate, dueDate);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("taskId", saved.getId().toString());
    payload.put("projectId", saved.getProjectId().toString());
    payload.put("oldDueDate", oldDueDate == null ? null : oldDueDate.toString());
    payload.put("newDueDate", dueDate == null ? null : dueDate.toString());
    payload.put("actorId", actorId == null ? null : actorId.toString());
    writeTaskEvent("TASK_DUE_DATE_CHANGED", saved, payload);
    return saved;
  }

  /**
   * V22: gorevi bir kisiye atar ({@code assigneeId == null} atamayi kaldirir). Atanan, gorevin
   * workspace'inde VIEWER disi bir uye olmalidir ({@code workspace_users} RLS'siz oldugundan
   * workspace id acikca verilir). Atanan otomatik izleyici olur; {@code TASK_ASSIGNED} izlemese
   * bile atanana bildirilir (InboxFanoutService).
   */
  @Transactional
  public Task assign(UUID taskId, UUID assigneeId, UUID actorId) {
    Task task = requireTask(taskId);
    UUID oldAssigneeId = task.getAssigneeId();
    if (Objects.equals(oldAssigneeId, assigneeId)) {
      return task;
    }
    rejectIfApprovedTask(task, "Onaylanmis gorevin atanani degistirilemez.");
    if (assigneeId != null) {
      String role =
          workspaceUserRepository
              .findByWorkspaceIdAndUserId(task.getWorkspaceId(), assigneeId)
              .map(WorkspaceUser::getRole)
              .orElseThrow(() -> new BusinessRuleException("Atanan kisi workspace uyesi degil."));
      if (WorkspaceRole.VIEWER.equals(role)) {
        throw new BusinessRuleException("Salt-okunur (VIEWER) uyeye gorev atanamaz.");
      }
    }
    task.changeAssignee(assigneeId);
    Task saved = taskRepository.save(task);
    taskEventRepository.recordAssigneeChange(taskId, actorId, oldAssigneeId, assigneeId);
    if (assigneeId != null) {
      taskWatcherRepository.watch(taskId, assigneeId, saved.getWorkspaceId());
    }
    Map<String, Object> payload = basePayload(saved, actorId);
    payload.put("oldAssigneeId", oldAssigneeId == null ? null : oldAssigneeId.toString());
    payload.put("newAssigneeId", assigneeId == null ? null : assigneeId.toString());
    writeTaskEvent("TASK_ASSIGNED", saved, payload);
    return saved;
  }

  /**
   * V22: Markdown aciklama. Bos/yalniz bosluk {@code null} sayilir. Icerik olay payload'ina KONMAZ:
   * outbox + Kafka + WebSocket yayini buyuk metni tasimasin; istemci olay gelince detayi yeniden
   * ceker.
   */
  @Transactional
  public Task updateDescription(UUID taskId, String description, UUID actorId) {
    Task task = requireTask(taskId);
    String normalized = description == null || description.isBlank() ? null : description;
    String oldDescription = task.getDescription();
    if (Objects.equals(oldDescription, normalized)) {
      return task;
    }
    rejectIfApprovedTask(task, "Onaylanmis gorevin aciklamasi degistirilemez.");
    task.changeDescription(normalized);
    Task saved = taskRepository.save(task);
    taskEventRepository.recordDescriptionChange(
        taskId, actorId, length(oldDescription), length(normalized));
    writeTaskEvent("TASK_DESCRIPTION_CHANGED", saved, basePayload(saved, actorId));
    return saved;
  }

  /** Detay gorunumu (aciklama + izleyiciler) — liste yanitlari aciklamayi tasimaz. */
  @Transactional(readOnly = true)
  public TaskDetail getDetail(UUID taskId) {
    Task task = requireTask(taskId);
    return new TaskDetail(task, taskWatcherRepository.findWatcherIds(taskId));
  }

  /**
   * Izleme herkese acik (VIEWER dahil): izlemek okumaktir, gorevi degistirmez. Idempotent.
   * Kullanici workspace uyesi oldugu icin (WorkspaceContextFilter) ayrica uyelik kontrolu gerekmez.
   */
  @Transactional
  public TaskDetail watch(UUID taskId, UUID userId) {
    Task task = requireTask(taskId);
    taskWatcherRepository.watch(taskId, userId, task.getWorkspaceId());
    return new TaskDetail(task, taskWatcherRepository.findWatcherIds(taskId));
  }

  @Transactional
  public TaskDetail unwatch(UUID taskId, UUID userId) {
    Task task = requireTask(taskId);
    taskWatcherRepository.unwatch(taskId, userId);
    return new TaskDetail(task, taskWatcherRepository.findWatcherIds(taskId));
  }

  /** "Benim islerim": projeler arasi, onaylanmamis atanmis gorevler (keyset). */
  @Transactional(readOnly = true)
  public PageResponse<Task> listAssignedTo(UUID userId, int limit, String cursor) {
    Pageable pageable = PageRequest.of(0, limit + 1);
    List<Task> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = taskRepository.findFirstAssignedPage(userId, pageable);
    } else {
      TaskCursor decoded = TaskCursor.decode(cursor);
      rows =
          taskRepository.findNextAssignedPage(userId, decoded.createdAt(), decoded.id(), pageable);
    }
    boolean hasMore = rows.size() > limit;
    List<Task> page = hasMore ? rows.subList(0, limit) : rows;
    String nextCursor = null;
    if (hasMore) {
      Task last = page.get(page.size() - 1);
      nextCursor = new TaskCursor(last.getCreatedAt(), last.getId()).encode();
    }
    return new PageResponse<>(page, nextCursor, hasMore);
  }

  /** Gorev + izleyici kimlikleri; controller DTO'ya cevirir. */
  public record TaskDetail(Task task, List<UUID> watcherIds) {
    public TaskDetail {
      watcherIds = List.copyOf(watcherIds);
    }
  }

  private static int length(String value) {
    return value == null ? 0 : value.length();
  }

  private static void rejectIfApprovedTask(Task task, String message) {
    if (task.isApproved()) {
      throw new BusinessRuleException(message);
    }
  }

  /** Takvim gorunumu; aralik ust siniri controller'da uygulanir. */
  @Transactional(readOnly = true)
  public List<Task> listTasksByDueDate(UUID projectId, LocalDate from, LocalDate to) {
    if (to.isBefore(from)) {
      throw new BusinessRuleException("'to', 'from' tarihinden once olamaz.");
    }
    requireProject(projectId);
    return taskRepository.findByDueDateRange(projectId, from, to);
  }

  /**
   * Tamamlanan (Done) gorevi onaylar: gorev Kanban'dan kalkar, Tamamlananlar listesine gecer. Durum
   * 'Done' KALIR, bu yuzden analitik etkilenmez. Tekrar onay idempotent (ilk onay zamani korunur).
   */
  @Transactional
  public Task approve(UUID taskId, UUID actorId) {
    Task task = requireTask(taskId);
    if (task.isApproved()) {
      return task;
    }
    if (!TaskStatus.DONE.equals(task.getStatus())) {
      throw new BusinessRuleException("Yalniz 'Done' durumundaki gorev onaylanabilir.");
    }
    // Millisaniyeye kesilir: Tamamlananlar cursor'i epoch-milli tasir, esitlik karsilastirmasi
    // DB'deki degerle birebir tutmali.
    task.approve(actorId, clock.instant().truncatedTo(ChronoUnit.MILLIS));
    Task saved = taskRepository.save(task);
    taskEventRepository.recordApprovalChange(taskId, actorId, true);
    writeTaskEvent("TASK_APPROVED", saved, basePayload(saved, actorId));
    return saved;
  }

  /** Yanlislikla verilen onayi geri alir; gorev 'Done' olarak Kanban'a doner. */
  @Transactional
  public Task revokeApproval(UUID taskId, UUID actorId) {
    Task task = requireTask(taskId);
    if (!task.isApproved()) {
      return task;
    }
    task.revokeApproval();
    Task saved = taskRepository.save(task);
    taskEventRepository.recordApprovalChange(taskId, actorId, false);
    writeTaskEvent("TASK_APPROVAL_REVOKED", saved, basePayload(saved, actorId));
    return saved;
  }

  /**
   * V19: gorevi bir parent'a subtask olarak baglar / kaldirir ({@code parentTaskId == null}). Tek
   * seviye kurali: parent'in kendi parent'i OLAMAZ, hedef'in de zaten cocuklari OLAMAZ — ikisi ayni
   * anda gerceklesirse gercek bir agac olusurdu (kullanici karariyla bilerek engellendi, tag/
   * dependency'nin aksine kendi paketi yok, dogrudan TaskService'te — subtask salt tasks.parent_
   * task_id kolonu, ayri bir join tablosu gerektirmiyor).
   */
  @Transactional
  public Task setParent(UUID taskId, UUID parentTaskId, UUID actorId) {
    Task task = requireTask(taskId);
    if (Objects.equals(task.getParentTaskId(), parentTaskId)) {
      return task;
    }
    if (parentTaskId == null) {
      return removeParent(taskId, actorId);
    }
    if (taskId.equals(parentTaskId)) {
      throw new BusinessRuleException("Bir gorev kendisinin alt gorevi olamaz.");
    }
    rejectIfApproved(task);
    Task parent = requireTask(parentTaskId);
    if (!parent.getProjectId().equals(task.getProjectId())) {
      throw new BusinessRuleException("Alt gorev iliskisi ayni proje icinde kurulabilir.");
    }
    if (parent.getParentTaskId() != null) {
      throw new BusinessRuleException("Bir alt gorev baska bir gorevin ust gorevi olamaz.");
    }
    if (!taskRepository.findByParentTaskIdOrderByTaskNumber(taskId).isEmpty()) {
      throw new BusinessRuleException("Alt gorevi olan bir gorev subtask yapilamaz.");
    }
    task.changeParent(parentTaskId);
    Task saved = taskRepository.save(task);
    writeTaskEvent("TASK_PARENT_CHANGED", saved, parentPayload(saved, parentTaskId, actorId));
    return saved;
  }

  @Transactional
  public Task removeParent(UUID taskId, UUID actorId) {
    Task task = requireTask(taskId);
    if (task.getParentTaskId() == null) {
      return task;
    }
    rejectIfApproved(task);
    task.changeParent(null);
    Task saved = taskRepository.save(task);
    writeTaskEvent("TASK_PARENT_CHANGED", saved, parentPayload(saved, null, actorId));
    return saved;
  }

  /** TaskDialog'un alt gorev listesi icin — tam gorev alani gerekir, sadece sayi degil. */
  @Transactional(readOnly = true)
  public List<Task> listChildren(UUID taskId) {
    return taskRepository.findByParentTaskIdOrderByTaskNumber(taskId);
  }

  /** Liste uc noktalarinda N+1'i onlemek icin batch: parent basina (toplam, Done sayisi). */
  @Transactional(readOnly = true)
  public Map<UUID, int[]> subtaskCounts(List<UUID> taskIds) {
    if (taskIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, int[]> result = new LinkedHashMap<>();
    for (Object[] row : taskRepository.countChildrenByParentTaskIds(taskIds)) {
      UUID parentId = (UUID) row[0];
      long total = (Long) row[1];
      long done = row[2] == null ? 0L : (Long) row[2];
      result.put(parentId, new int[] {(int) total, (int) done});
    }
    return result;
  }

  private static Map<String, Object> parentPayload(Task task, UUID parentTaskId, UUID actorId) {
    Map<String, Object> payload = basePayload(task, actorId);
    payload.put("parentTaskId", parentTaskId == null ? null : parentTaskId.toString());
    return payload;
  }

  /** TaskDependencyService'teki AYNI kilit kurali: onaylanmis gorev donuk. */
  private static void rejectIfApproved(Task task) {
    if (task.isApproved()) {
      throw new BusinessRuleException("Onaylanmis gorevin ust gorevi degistirilemez.");
    }
  }

  /**
   * Soft delete. Gorev bir sprint'teyse once sprint'ten cikarilir: aktif sprint'in taahhudunden
   * duser; tamamlanmis sprint'lerin velocity'si degismez (uyelik {@code completedAt} kesitinden
   * kurulur, bu olay kesitten sonradir). {@code TASK_DELETED} analitik read model'ini temizler.
   */
  @Transactional
  public void deleteTask(UUID taskId, UUID actorId) {
    Task task = requireTask(taskId);
    if (!taskRepository.findByParentTaskIdOrderByTaskNumber(taskId).isEmpty()) {
      throw new BusinessRuleException(
          "Alt gorevleri olan bir gorev silinemez, once alt gorevleri kaldirin.");
    }
    if (task.getSprintId() != null) {
      taskEventRepository.recordSprintChange(taskId, actorId, task.getSprintId(), null);
      task.changeSprint(null);
    }
    task.markDeleted(actorId, clock.instant());
    Task saved = taskRepository.save(task);
    taskEventRepository.recordDeletion(taskId, actorId);
    writeTaskEvent("TASK_DELETED", saved, basePayload(saved, actorId));
  }

  @Transactional(readOnly = true)
  public PageResponse<Task> listApprovedTasks(UUID projectId, int limit, String cursor) {
    requireProject(projectId);
    Pageable pageable = PageRequest.of(0, limit + 1);
    List<Task> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = taskRepository.findFirstApprovedPage(projectId, pageable);
    } else {
      TaskCursor decoded = TaskCursor.decode(cursor);
      rows =
          taskRepository.findNextApprovedPage(
              projectId, decoded.createdAt(), decoded.id(), pageable);
    }
    boolean hasMore = rows.size() > limit;
    List<Task> page = hasMore ? rows.subList(0, limit) : rows;
    String nextCursor = null;
    if (hasMore) {
      Task last = page.get(page.size() - 1);
      // TaskCursor'in ilk alani burada onay zamanidir (siralama anahtari).
      nextCursor = new TaskCursor(last.getApprovedAt(), last.getId()).encode();
    }
    return new PageResponse<>(page, nextCursor, hasMore);
  }

  /**
   * Yeni bir bitis tarihi bugunden (is saat dilimi, bkz. {@code ClockConfig}) once olamaz. Yalniz
   * DEGISEN tarihe uygulanir: gecikmis bir gorevin mevcut tarihi korunur, {@code null} serbesttir.
   */
  private void rejectPastDueDate(LocalDate dueDate) {
    if (dueDate != null && dueDate.isBefore(LocalDate.now(clock))) {
      throw new BusinessRuleException("Bitis tarihi gecmis bir gun olamaz.");
    }
  }

  private static Map<String, Object> basePayload(Task task, UUID actorId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", task.getProjectId().toString());
    payload.put("actorId", actorId == null ? null : actorId.toString());
    return payload;
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
    // isDeleted: @SQLRestriction'a ek savunma (ayni persistence context'te onceden yuklenmis
    // bir entity filtreden gecmeden donebilir).
    return taskRepository
        .findById(taskId)
        .filter(task -> !task.isDeleted())
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
