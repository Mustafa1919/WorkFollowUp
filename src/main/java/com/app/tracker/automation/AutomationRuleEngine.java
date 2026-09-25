package com.app.tracker.automation;

import com.app.tracker.core.idempotency.ProcessedEventStore;
import com.app.tracker.dependency.repository.TaskDependencyRepository;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.repository.NotificationRepository;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.repository.TaskRepository;
import com.app.tracker.task.repository.TaskWatcherRepository;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dalga 3.1 (ADR-0016) — {@code task.events} tabanli uc sablonun is mantigi: {@code
 * SUBTASK_ALL_DONE_PARENT_TO_REVIEW}, {@code BLOCKER_DONE_NOTIFY}, {@code
 * ASSIGNED_TODO_TO_IN_PROGRESS}. {@code PR_MERGE_TO_DONE} burada DEGIL — {@code
 * GithubEventProcessor} icinde ayrica ele alinir (webhook zaten kendi zincirinde).
 *
 * <p>{@code AutomationEventConsumer}'dan (Kafka listener, RLS baglami {@code TenantExecutor} ile
 * kurulmus) cagirilir. Idempotency: {@code ProcessedEventStore#markProcessed(CONSUMER, eventId)} bu
 * is yazimiyla AYNI transaction'da, tek noktada — bir olay icin uygulanabilecek TUM sablonlar
 * (parent-to-review + blocker-notify, ayni {@code TASK_STATUS_UPDATED} olayina tepki verir) AYNI
 * cagrida degerlendirilir, ayri ayri markProcessed cagirmaya gerek yoktur.
 */
@Service
@Profile("!migrate")
public class AutomationRuleEngine {

  public static final String CONSUMER = "automation-rules";

  private final ProcessedEventStore processedEventStore;
  private final AutomationRuleService automationRuleService;
  private final TaskRepository taskRepository;
  private final TaskDependencyRepository taskDependencyRepository;
  private final TaskWatcherRepository taskWatcherRepository;
  private final WorkspaceUserRepository workspaceUserRepository;
  private final ProjectRepository projectRepository;
  private final NotificationRepository notificationRepository;
  private final TaskService taskService;

  public AutomationRuleEngine(
      ProcessedEventStore processedEventStore,
      AutomationRuleService automationRuleService,
      TaskRepository taskRepository,
      TaskDependencyRepository taskDependencyRepository,
      TaskWatcherRepository taskWatcherRepository,
      WorkspaceUserRepository workspaceUserRepository,
      ProjectRepository projectRepository,
      NotificationRepository notificationRepository,
      TaskService taskService) {
    this.processedEventStore = processedEventStore;
    this.automationRuleService = automationRuleService;
    this.taskRepository = taskRepository;
    this.taskDependencyRepository = taskDependencyRepository;
    this.taskWatcherRepository = taskWatcherRepository;
    this.workspaceUserRepository = workspaceUserRepository;
    this.projectRepository = projectRepository;
    this.notificationRepository = notificationRepository;
    this.taskService = taskService;
  }

  /** {@code TASK_STATUS_UPDATED} olayi, {@code newStatus == Done}. */
  @Transactional
  public void onTaskDone(UUID eventId, UUID taskId) {
    if (!processedEventStore.markProcessed(CONSUMER, eventId)) {
      return;
    }
    Task task = taskRepository.findById(taskId).orElse(null);
    if (task == null) {
      return;
    }
    applyParentToReview(task);
    applyBlockerNotify(task);
  }

  /** {@code TASK_ASSIGNED} olayi, {@code newAssigneeId != null}. */
  @Transactional
  public void onTaskAssigned(UUID eventId, UUID taskId) {
    if (!processedEventStore.markProcessed(CONSUMER, eventId)) {
      return;
    }
    Task task = taskRepository.findById(taskId).orElse(null);
    if (task == null
        || task.getAssigneeId() == null
        || !TaskStatus.TO_DO.equals(task.getStatus())
        || !automationRuleService.isEnabled(
            task.getProjectId(), AutomationTemplateKey.ASSIGNED_TODO_TO_IN_PROGRESS)) {
      return;
    }
    taskService.updateStatus(taskId, TaskStatus.IN_PROGRESS, AutomationActor.SYSTEM_USER_ID);
  }

  private void applyParentToReview(Task task) {
    UUID parentId = task.getParentTaskId();
    if (parentId == null
        || !automationRuleService.isEnabled(
            task.getProjectId(), AutomationTemplateKey.SUBTASK_ALL_DONE_PARENT_TO_REVIEW)) {
      return;
    }
    Task parent = taskRepository.findById(parentId).orElse(null);
    if (parent == null
        || parent.isApproved()
        || (!TaskStatus.TO_DO.equals(parent.getStatus())
            && !TaskStatus.IN_PROGRESS.equals(parent.getStatus()))) {
      return;
    }
    List<Task> siblings = taskRepository.findByParentTaskIdOrderByTaskNumber(parentId);
    boolean allDone =
        !siblings.isEmpty()
            && siblings.stream().allMatch(t -> TaskStatus.DONE.equals(t.getStatus()));
    if (allDone) {
      taskService.updateStatus(parentId, TaskStatus.REVIEW, AutomationActor.SYSTEM_USER_ID);
    }
  }

  private void applyBlockerNotify(Task blockerTask) {
    for (UUID blockedTaskId : taskDependencyRepository.findBlockedTaskIds(blockerTask.getId())) {
      Task blocked = taskRepository.findById(blockedTaskId).orElse(null);
      if (blocked == null
          || !automationRuleService.isEnabled(
              blocked.getProjectId(), AutomationTemplateKey.BLOCKER_DONE_NOTIFY)) {
        continue;
      }
      notifyBlockerResolved(blocked, blockerTask);
    }
  }

  private void notifyBlockerResolved(Task blocked, Task blockerTask) {
    Optional<Project> maybeProject = projectRepository.findById(blocked.getProjectId());
    if (maybeProject.isEmpty()) {
      return;
    }
    Project project = maybeProject.get();
    Set<UUID> members =
        workspaceUserRepository.findByWorkspaceId(project.getWorkspaceId()).stream()
            .map(WorkspaceUser::getUserId)
            .collect(Collectors.toSet());
    Set<UUID> recipients = new LinkedHashSet<>();
    if (blocked.getAssigneeId() != null) {
      recipients.add(blocked.getAssigneeId());
    }
    recipients.addAll(taskWatcherRepository.findWatcherIds(blocked.getId()));
    recipients.retainAll(members);
    if (recipients.isEmpty()) {
      return;
    }
    String header = project.getKey() + "-" + blocked.getTaskNumber();
    String title = "Sizi bloklayan görev tamamlandı: " + header;
    String body =
        "\""
            + blockerTask.getTitle()
            + "\" tamamlandı, \""
            + blocked.getTitle()
            + "\" artık ilerleyebilir.";
    Instant now = Instant.now();
    for (UUID recipientId : recipients) {
      Notification notification =
          Notification.of(
              UUID.randomUUID(),
              project.getWorkspaceId(),
              recipientId,
              "AUTOMATION_BLOCKER_DONE",
              blocked.getId(),
              project.getId(),
              title,
              body,
              now);
      notificationRepository.save(notification);
    }
  }
}
