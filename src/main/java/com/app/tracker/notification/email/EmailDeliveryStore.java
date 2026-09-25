package com.app.tracker.notification.email;

import com.app.tracker.core.idempotency.ProcessedEventStore;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.task.repository.TaskRepository;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KISA transaction'lar — SMTP cagrisi bunlarin DISINDA yapilir ({@code SlackDeliveryStore} ile AYNI
 * desen, bkz. ADR-0006 madde 4). Ayri bir bean: {@code @Transactional} self-invocation'da devreye
 * girmez.
 */
@Service
@Profile("!migrate")
public class EmailDeliveryStore {

  private final UserRepository userRepository;
  private final TaskRepository taskRepository;
  private final ProjectRepository projectRepository;
  private final WorkspaceUserRepository workspaceUserRepository;
  private final ProcessedEventStore processedEventStore;

  public EmailDeliveryStore(
      UserRepository userRepository,
      TaskRepository taskRepository,
      ProjectRepository projectRepository,
      WorkspaceUserRepository workspaceUserRepository,
      ProcessedEventStore processedEventStore) {
    this.userRepository = userRepository;
    this.taskRepository = taskRepository;
    this.projectRepository = projectRepository;
    this.workspaceUserRepository = workspaceUserRepository;
    this.processedEventStore = processedEventStore;
  }

  /** Gorev basligi/anahtari icin — proje veya gorev bulunamazsa bos (RLS: baska tenant asla). */
  public record TaskSummary(UUID projectId, String projectKey, int taskNumber, String title) {}

  @Transactional(readOnly = true)
  public Optional<String> findUserEmail(UUID userId) {
    return userRepository.findById(userId).map(User::getEmail);
  }

  @Transactional(readOnly = true)
  public Optional<TaskSummary> findTaskSummary(UUID taskId) {
    return taskRepository
        .findById(taskId)
        .flatMap(
            task ->
                projectRepository
                    .findById(task.getProjectId())
                    .map(
                        project ->
                            new TaskSummary(
                                project.getId(),
                                project.getKey(),
                                task.getTaskNumber(),
                                task.getTitle())));
  }

  @Transactional(readOnly = true)
  public boolean isWorkspaceMember(UUID workspaceId, UUID userId) {
    return workspaceUserRepository.findByWorkspaceIdAndUserId(workspaceId, userId).isPresent();
  }

  @Transactional(readOnly = true)
  public boolean alreadyProcessed(String consumer, UUID eventId) {
    return processedEventStore.isProcessed(consumer, eventId);
  }

  @Transactional
  public void markProcessed(String consumer, UUID eventId) {
    processedEventStore.markProcessed(consumer, eventId);
  }
}
