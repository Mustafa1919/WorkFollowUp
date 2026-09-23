package com.app.tracker.analytics.service;

import com.app.tracker.analytics.model.TaskAnalytics;
import com.app.tracker.analytics.repository.TaskAnalyticsRepository;
import com.app.tracker.core.idempotency.ProcessedEventStore;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bir {@code TASK_STATUS_UPDATED} olayini {@code task_analytics}'e yansitir. Tenant context'i
 * CAGIRAN kurar ({@code TenantExecutor.runAs}, bkz. Faz 1 "Tenant-Iterating" deseni): bu metot
 * {@code @Transactional} oldugundan context transaction baslarken hazir olmak zorundadir.
 */
@Service
public class CycleTimeProjector {

  public static final String CONSUMER = "analytics-cycle-time";

  private final ProcessedEventStore processedEventStore;
  private final TaskAnalyticsRepository taskAnalyticsRepository;

  public CycleTimeProjector(
      ProcessedEventStore processedEventStore, TaskAnalyticsRepository taskAnalyticsRepository) {
    this.processedEventStore = processedEventStore;
    this.taskAnalyticsRepository = taskAnalyticsRepository;
  }

  /**
   * Idempotency isaretlemesi ile projeksiyon yazimi AYNI transaction'dadir (bkz. {@link
   * ProcessedEventStore}).
   */
  @Transactional
  public void project(
      UUID eventId,
      UUID taskId,
      UUID workspaceId,
      UUID projectId,
      String newStatus,
      Instant occurredAt) {
    if (!processedEventStore.markProcessed(CONSUMER, eventId)) {
      return;
    }
    TaskAnalytics analytics =
        taskAnalyticsRepository
            .findByIdForUpdate(taskId)
            .orElseGet(() -> TaskAnalytics.create(taskId, workspaceId, projectId));
    if (analytics.applyStatusChange(newStatus, occurredAt)) {
      taskAnalyticsRepository.save(analytics);
    }
  }

  /**
   * Silinen gorevin read model satirini kaldirir. Olaylar ayni anahtarla (taskId) ayni partition'a
   * gittigi icin silmeden SONRA ayni gorev icin durum olayi gelmez (DLT replay'i haric; bilinen
   * sinir: eski bir olay replay edilirse satir yeniden olusur).
   */
  @Transactional
  public void forget(UUID eventId, UUID taskId) {
    if (!processedEventStore.markProcessed(CONSUMER, eventId)) {
      return;
    }
    taskAnalyticsRepository.deleteById(taskId);
  }
}
