package com.app.tracker.flow.service;

import com.app.tracker.analytics.model.TaskAnalytics;
import com.app.tracker.analytics.repository.ProjectMetricsRepository;
import com.app.tracker.analytics.repository.ProjectMetricsRepository.CycleTimeStats;
import com.app.tracker.analytics.repository.TaskAnalyticsRepository;
import com.app.tracker.flow.AgingWipLevels;
import com.app.tracker.flow.repository.TaskAgingAlertRepository;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.repository.NotificationRepository;
import com.app.tracker.project.model.Project;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskRepository;
import com.app.tracker.task.repository.TaskWatcherRepository;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.time.Instant;
import java.util.ArrayList;
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
 * Dalga 2.1 — bir proje icin Aging WIP esik gecislerini kontrol edip Inbox bildirimi uretir. {@link
 * AgingWipJob}'dan (ayri bean, self-invocation'da {@code @Transactional} proxy'sinin atlanmamasi
 * icin — {@code MeetingReminderJob}/{@code MeetingReminderService} ile AYNI ayristirma) cagirilir.
 *
 * <p>Seviye tanimi {@link AgingWipLevels}'ta, okuma servisiyle ({@code AgingWipService})
 * PAYLASILIR. Bildirim yalniz seviye YUKSELDIGINDE gider (0->1, 1->2 veya dogrudan 0->2); ayni
 * seviyede tekrar tekrar bildirim gitmez ({@code task_aging_alerts} bu amacla tutulur). Gorev artik
 * esigin altina donerse (Done oldu, reopen sonrasi henuz genc, vs.) kayit temizlenir ki ileride
 * yeniden esigi asarsa bildirim TEKRAR gidebilsin.
 */
@Service
@Profile("!migrate")
public class AgingWipAlertService {

  private final ProjectMetricsRepository projectMetricsRepository;
  private final TaskAnalyticsRepository taskAnalyticsRepository;
  private final TaskAgingAlertRepository taskAgingAlertRepository;
  private final TaskRepository taskRepository;
  private final TaskWatcherRepository taskWatcherRepository;
  private final WorkspaceUserRepository workspaceUserRepository;
  private final NotificationRepository notificationRepository;

  public AgingWipAlertService(
      ProjectMetricsRepository projectMetricsRepository,
      TaskAnalyticsRepository taskAnalyticsRepository,
      TaskAgingAlertRepository taskAgingAlertRepository,
      TaskRepository taskRepository,
      TaskWatcherRepository taskWatcherRepository,
      WorkspaceUserRepository workspaceUserRepository,
      NotificationRepository notificationRepository) {
    this.projectMetricsRepository = projectMetricsRepository;
    this.taskAnalyticsRepository = taskAnalyticsRepository;
    this.taskAgingAlertRepository = taskAgingAlertRepository;
    this.taskRepository = taskRepository;
    this.taskWatcherRepository = taskWatcherRepository;
    this.workspaceUserRepository = workspaceUserRepository;
    this.notificationRepository = notificationRepository;
  }

  @Transactional
  public List<Notification> checkProject(Project project) {
    CycleTimeStats stats = projectMetricsRepository.cycleTimeStats(project.getId(), Instant.EPOCH);
    if (stats.sampleSize() < AgingWipLevels.MIN_SAMPLE_SIZE || stats.p85() == null) {
      return List.of();
    }
    double p85Seconds = stats.p85();
    Instant now = Instant.now();
    Set<UUID> members =
        workspaceUserRepository.findByWorkspaceId(project.getWorkspaceId()).stream()
            .map(WorkspaceUser::getUserId)
            .collect(Collectors.toSet());
    List<TaskAnalytics> open = taskAnalyticsRepository.findOpenByProjectId(project.getId());
    Set<UUID> openTaskIds = open.stream().map(TaskAnalytics::getTaskId).collect(Collectors.toSet());
    for (UUID alertedTaskId :
        taskAgingAlertRepository.findAlertedTaskIdsForProject(project.getId())) {
      if (!openTaskIds.contains(alertedTaskId)) {
        taskAgingAlertRepository.clear(alertedTaskId);
      }
    }
    List<Notification> created = new ArrayList<>();
    for (TaskAnalytics analytics : open) {
      long ageSeconds = now.getEpochSecond() - analytics.getFirstInProgressAt().getEpochSecond();
      int level = AgingWipLevels.levelFor(ageSeconds, p85Seconds);
      int currentLevel = taskAgingAlertRepository.currentLevel(analytics.getTaskId());
      if (level == 0) {
        if (currentLevel > 0) {
          taskAgingAlertRepository.clear(analytics.getTaskId());
        }
        continue;
      }
      if (level <= currentLevel) {
        continue;
      }
      taskAgingAlertRepository.upsertLevel(analytics.getTaskId(), project.getWorkspaceId(), level);
      created.addAll(notify(project, analytics.getTaskId(), level, members, now));
    }
    return created;
  }

  private List<Notification> notify(
      Project project, UUID taskId, int level, Set<UUID> members, Instant now) {
    Optional<Task> maybeTask = taskRepository.findById(taskId);
    if (maybeTask.isEmpty()) {
      return List.of();
    }
    Task task = maybeTask.get();
    Set<UUID> recipients = new LinkedHashSet<>();
    if (task.getAssigneeId() != null) {
      recipients.add(task.getAssigneeId());
    }
    recipients.addAll(taskWatcherRepository.findWatcherIds(taskId));
    recipients.retainAll(members);
    if (recipients.isEmpty()) {
      return List.of();
    }
    String title =
        level >= 2
            ? "Görev ciddi biçimde takıldı: " + project.getKey() + "-" + task.getTaskNumber()
            : "Görev takılmış olabilir: " + project.getKey() + "-" + task.getTaskNumber();
    String body =
        task.getTitle()
            + (level >= 2
                ? " normal sürenin 2 katından uzun süredir devam ediyor."
                : " normal süreden uzun süredir devam ediyor.");
    List<Notification> created = new ArrayList<>();
    for (UUID recipientId : recipients) {
      Notification notification =
          Notification.of(
              UUID.randomUUID(),
              project.getWorkspaceId(),
              recipientId,
              "TASK_AGING",
              taskId,
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
