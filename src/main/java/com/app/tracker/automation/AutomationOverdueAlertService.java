package com.app.tracker.automation;

import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.repository.NotificationRepository;
import com.app.tracker.project.model.Project;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskRepository;
import com.app.tracker.task.repository.TaskWatcherRepository;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dalga 3.1 (OVERDUE_NOTIFY) — bir proje icin bitis tarihi gecmis gorevleri bulur, gunde bir kez
 * (bkz. {@link AutomationNotificationLedgerRepository}) atanan+izleyicilere Inbox bildirimi uretir.
 * {@link AutomationOverdueJob}'dan (ayri bean, self-invocation'da {@code @Transactional}
 * proxy'sinin atlanmamasi icin — {@code AgingWipJob}/{@code AgingWipAlertService} ile AYNI
 * ayristirma) cagirilir. "Bugun" is saat diliminde ({@code Clock} bean'i) hesaplanir.
 */
@Service
@Profile("!migrate")
public class AutomationOverdueAlertService {

  private final AutomationRuleService automationRuleService;
  private final AutomationNotificationLedgerRepository ledgerRepository;
  private final TaskRepository taskRepository;
  private final TaskWatcherRepository taskWatcherRepository;
  private final WorkspaceUserRepository workspaceUserRepository;
  private final NotificationRepository notificationRepository;
  private final Clock clock;

  public AutomationOverdueAlertService(
      AutomationRuleService automationRuleService,
      AutomationNotificationLedgerRepository ledgerRepository,
      TaskRepository taskRepository,
      TaskWatcherRepository taskWatcherRepository,
      WorkspaceUserRepository workspaceUserRepository,
      NotificationRepository notificationRepository,
      Clock clock) {
    this.automationRuleService = automationRuleService;
    this.ledgerRepository = ledgerRepository;
    this.taskRepository = taskRepository;
    this.taskWatcherRepository = taskWatcherRepository;
    this.workspaceUserRepository = workspaceUserRepository;
    this.notificationRepository = notificationRepository;
    this.clock = clock;
  }

  @Transactional
  public List<Notification> checkProject(Project project) {
    if (!automationRuleService.isEnabled(project.getId(), AutomationTemplateKey.OVERDUE_NOTIFY)) {
      return List.of();
    }
    LocalDate today = LocalDate.now(clock);
    List<Task> overdue = taskRepository.findOverdueByProjectId(project.getId(), today);
    if (overdue.isEmpty()) {
      return List.of();
    }
    Set<UUID> members =
        workspaceUserRepository.findByWorkspaceId(project.getWorkspaceId()).stream()
            .map(WorkspaceUser::getUserId)
            .collect(Collectors.toSet());
    Instant now = Instant.now();
    List<Notification> created = new ArrayList<>();
    for (Task task : overdue) {
      if (!ledgerRepository.markSent(
          AutomationTemplateKey.OVERDUE_NOTIFY.name(),
          task.getId(),
          project.getWorkspaceId(),
          today)) {
        continue;
      }
      created.addAll(notify(project, task, members, now));
    }
    return created;
  }

  private List<Notification> notify(Project project, Task task, Set<UUID> members, Instant now) {
    Set<UUID> recipients = new LinkedHashSet<>();
    if (task.getAssigneeId() != null) {
      recipients.add(task.getAssigneeId());
    }
    recipients.addAll(taskWatcherRepository.findWatcherIds(task.getId()));
    recipients.retainAll(members);
    if (recipients.isEmpty()) {
      return List.of();
    }
    String header = project.getKey() + "-" + task.getTaskNumber();
    String title = "Süresi geçti: " + header;
    String body = task.getTitle() + " için bitiş tarihi (" + task.getDueDate() + ") geçti.";
    List<Notification> created = new ArrayList<>();
    for (UUID recipientId : recipients) {
      Notification notification =
          Notification.of(
              UUID.randomUUID(),
              project.getWorkspaceId(),
              recipientId,
              "AUTOMATION_OVERDUE",
              task.getId(),
              project.getId(),
              title,
              body,
              now);
      notificationRepository.save(notification);
      created.add(notification);
    }
    return created;
  }
}
