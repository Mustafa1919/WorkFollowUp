package com.app.tracker.dependency.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.outbox.OutboxEventRepository;
import com.app.tracker.dependency.dto.TaskRefResponse;
import com.app.tracker.dependency.repository.TaskDependencyRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskEventRepository;
import com.app.tracker.task.repository.TaskRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * RAKIP_ANALIZI.md Bolum 3 — Dependency (Blocked by/Blocking), Subtask'in yanindaki ikinci parca.
 * TagService ile AYNI desen: idempotent link/unlink, batch N+1 onleme, approved-task kilidi.
 * Kullanici karariyla kapsam: sadece BILGILENDIRICI (durum gecisini engellemez), workspace icinde
 * herhangi proje arasinda kurulabilir (Subtask'in aksine proje siniri YOK). {@code task_events}'e
 * yazim Dalga 1.5 (Activity sekmesi) ile eklendi — analitik worker'lar hala bu event tipini
 * tuketmiyor, yalniz Activity sekmesi okuyor.
 */
@Service
public class TaskDependencyService {

  private static final String TASK_EVENTS_TOPIC = "task.events";

  private final TaskDependencyRepository taskDependencyRepository;
  private final TaskRepository taskRepository;
  private final TaskEventRepository taskEventRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  public TaskDependencyService(
      TaskDependencyRepository taskDependencyRepository,
      TaskRepository taskRepository,
      TaskEventRepository taskEventRepository,
      OutboxEventRepository outboxEventRepository,
      ObjectMapper objectMapper) {
    this.taskDependencyRepository = taskDependencyRepository;
    this.taskRepository = taskRepository;
    this.taskEventRepository = taskEventRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
  }

  /** {@code blockedTaskId}, {@code blockingTaskId} tarafindan bloklanir. */
  @Transactional
  public Task link(UUID blockedTaskId, UUID blockingTaskId, UUID actorId) {
    if (blockedTaskId.equals(blockingTaskId)) {
      throw new BusinessRuleException("Bir gorev kendisini bloklayamaz.");
    }
    Task blocked = requireTask(blockedTaskId);
    Task blocking = requireTask(blockingTaskId);
    if (!blocked.getWorkspaceId().equals(blocking.getWorkspaceId())) {
      throw new BusinessRuleException("Farkli workspace'lerdeki gorevler birbirini bloklayamaz.");
    }
    rejectIfApproved(blocked);
    rejectIfApproved(blocking);
    if (taskDependencyRepository.existsReverse(blockingTaskId, blockedTaskId)) {
      throw new BusinessRuleException(
          "Bu iki gorev zaten ters yonde birbirini bloklu — dongu olusturulamaz.");
    }
    if (taskDependencyRepository.link(blockingTaskId, blockedTaskId, blocked.getWorkspaceId())) {
      taskEventRepository.recordDependencyChanged(blockedTaskId, blockingTaskId, actorId, true);
      writeDependencyChanged(blocked, blocking, "TASK_DEPENDENCY_ADDED");
    }
    return blocked;
  }

  /** Idempotent unlink; zaten baglanmamissa no-op (outbox'a/tarihceye yazilmaz). */
  @Transactional
  public Task unlink(UUID blockedTaskId, UUID blockingTaskId, UUID actorId) {
    Task blocked = requireTask(blockedTaskId);
    Task blocking = requireTask(blockingTaskId);
    rejectIfApproved(blocked);
    rejectIfApproved(blocking);
    if (taskDependencyRepository.unlink(blockingTaskId, blockedTaskId)) {
      taskEventRepository.recordDependencyChanged(blockedTaskId, blockingTaskId, actorId, false);
      writeDependencyChanged(blocked, blocking, "TASK_DEPENDENCY_REMOVED");
    }
    return blocked;
  }

  public record DependencySummary(List<TaskRefResponse> blocking, List<TaskRefResponse> blockedBy) {

    /** TaskResponse ile AYNI EI_EXPOSE_REP savunmasi: List.copyOf. */
    public DependencySummary {
      blocking = List.copyOf(blocking);
      blockedBy = List.copyOf(blockedBy);
    }

    public static DependencySummary empty() {
      return new DependencySummary(List.of(), List.of());
    }
  }

  @Transactional(readOnly = true)
  public DependencySummary dependenciesForTask(UUID taskId) {
    return dependenciesForTasks(List.of(taskId)).getOrDefault(taskId, DependencySummary.empty());
  }

  /** Liste uc noktalarinda N+1'i onlemek icin TEK sorguda batch. */
  @Transactional(readOnly = true)
  public Map<UUID, DependencySummary> dependenciesForTasks(List<UUID> taskIds) {
    Map<UUID, List<TaskRefResponse>> blockingByTask = new LinkedHashMap<>();
    Map<UUID, List<TaskRefResponse>> blockedByByTask = new LinkedHashMap<>();
    for (TaskDependencyRepository.DependencyRow row :
        taskDependencyRepository.findForTasks(taskIds)) {
      TaskRefResponse ref =
          new TaskRefResponse(
              row.otherTaskId(), row.otherTaskNumber(), row.otherTitle(), row.otherStatus());
      Map<UUID, List<TaskRefResponse>> target =
          row.taskIsBlocker() ? blockingByTask : blockedByByTask;
      target.computeIfAbsent(row.taskId(), k -> new ArrayList<>()).add(ref);
    }
    Map<UUID, DependencySummary> result = new LinkedHashMap<>();
    for (UUID taskId : taskIds) {
      result.put(
          taskId,
          new DependencySummary(
              blockingByTask.getOrDefault(taskId, List.of()),
              blockedByByTask.getOrDefault(taskId, List.of())));
    }
    return result;
  }

  /** TaskService/TagService'teki AYNI kilit kurali: onaylanmis gorev donuk. */
  private static void rejectIfApproved(Task task) {
    if (task.isApproved()) {
      throw new BusinessRuleException("Onaylanmis gorevin bagimliliklari degistirilemez.");
    }
  }

  /**
   * Bilerek {@code task_events}'e YAZILMAZ — TagService#writeTaskTagsChanged ile AYNI gerekce:
   * analitik worker'lar bu event tipini tuketmiyor. Outbox'a WebSocket fan-out + gelecekteki Inbox
   * icin yazilir.
   */
  private void writeDependencyChanged(Task blocked, Task blocking, String eventType) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("blockedTaskId", blocked.getId().toString());
    payload.put("blockingTaskId", blocking.getId().toString());
    payload.put("projectId", blocked.getProjectId().toString());
    outboxEventRepository.write(
        TASK_EVENTS_TOPIC,
        eventType,
        blocked.getId(),
        blocked.getWorkspaceId(),
        objectMapper.writeValueAsString(payload));
  }

  private Task requireTask(UUID taskId) {
    return taskRepository
        .findById(taskId)
        .filter(task -> !task.isDeleted())
        .orElseThrow(() -> new ResourceNotFoundException("Gorev bulunamadi."));
  }
}
