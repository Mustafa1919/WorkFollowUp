package com.app.tracker.notification.service;

import com.app.tracker.core.idempotency.ProcessedEventStore;
import com.app.tracker.notification.service.SlackMessageFormatter.TaskSummary;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.task.repository.TaskRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bildirim worker'inin KISA transaction'lari: gorev ozeti okuma ve teslimat isareti. Dis HTTP
 * cagrisi bu transaction'larin ICINDE degil ARASINDA yapilir (bkz. {@link
 * ProcessedEventStore#isProcessed}); ayri bir bean, cunku {@code @Transactional} ayni sinif icinden
 * cagrilinca (self-invocation) devreye girmez ve TenancyGuardAspect da yalniz
 * {@code @Transactional} sinirlarinda tenant baglamini kurar.
 */
@Service
@Profile("!migrate")
public class SlackDeliveryStore {

  private final TaskRepository taskRepository;
  private final ProjectRepository projectRepository;
  private final ProcessedEventStore processedEventStore;

  public SlackDeliveryStore(
      TaskRepository taskRepository,
      ProjectRepository projectRepository,
      ProcessedEventStore processedEventStore) {
    this.taskRepository = taskRepository;
    this.projectRepository = projectRepository;
    this.processedEventStore = processedEventStore;
  }

  /**
   * Bos: gorev veya projesi bulunamadi (RLS: baska tenant'inki ASLA eslesmez). Birincil DB'den
   * okur: olay az once commit edilmis bir yazmadan geliyor, replica gecikmesi bosluk yaratirdi.
   */
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
                                project.getKey(), task.getTaskNumber(), task.getTitle())));
  }

  @Transactional(readOnly = true)
  public boolean alreadyDelivered(UUID eventId) {
    return processedEventStore.isProcessed(SlackNotificationService.CONSUMER, eventId);
  }

  @Transactional
  public void markDelivered(UUID eventId) {
    processedEventStore.markProcessed(SlackNotificationService.CONSUMER, eventId);
  }
}
