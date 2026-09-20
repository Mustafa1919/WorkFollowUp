package com.app.tracker.analytics.service;

import com.app.tracker.analytics.model.SprintAnalytics;
import com.app.tracker.analytics.model.SprintSnapshot;
import com.app.tracker.analytics.repository.SprintAnalyticsRepository;
import com.app.tracker.analytics.repository.SprintSnapshotRepository;
import com.app.tracker.core.idempotency.ProcessedEventStore;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.model.SprintStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bir {@code SPRINT_COMPLETED} olayini {@code sprint_analytics}'e (Velocity / Spillover) yansitir.
 * Tenant context'i CAGIRAN kurar ({@code TenantExecutor.runAs}); bkz. {@link CycleTimeProjector}.
 *
 * <p>Iki giris yolu vardir ve ikisi de AYNI hesaplamayi kullanir: olay ({@link #project},
 * idempotency isaretlemesiyle) ve gece guvence job'i ({@link #reconcileMissing}). Hesaplama
 * deterministik oldugu icin ikisinin ayni sprint'i iki kez islemesi zararsizdir.
 */
@Service
public class VelocityProjector {

  public static final String CONSUMER = "analytics-velocity";

  private final ProcessedEventStore processedEventStore;
  private final SprintSnapshotRepository snapshotRepository;
  private final SprintAnalyticsRepository sprintAnalyticsRepository;

  public VelocityProjector(
      ProcessedEventStore processedEventStore,
      SprintSnapshotRepository snapshotRepository,
      SprintAnalyticsRepository sprintAnalyticsRepository) {
    this.processedEventStore = processedEventStore;
    this.snapshotRepository = snapshotRepository;
    this.sprintAnalyticsRepository = sprintAnalyticsRepository;
  }

  /**
   * Idempotency isaretlemesi ile projeksiyon yazimi AYNI transaction'dadir (bkz. {@link
   * ProcessedEventStore}).
   */
  @Transactional
  public void project(
      UUID eventId,
      UUID sprintId,
      UUID workspaceId,
      UUID projectId,
      String sprintName,
      Instant completedAt) {
    if (!processedEventStore.markProcessed(CONSUMER, eventId)) {
      return;
    }
    recalculate(sprintId, workspaceId, projectId, sprintName, completedAt);
  }

  /**
   * Aktif tenant context'indeki, read model'de karsiligi olmayan tamamlanmis sprint'leri hesaplar.
   *
   * @return hesaplanan sprint sayisi
   */
  @Transactional
  public int reconcileMissing() {
    List<Sprint> missing = sprintAnalyticsRepository.findWithoutAnalytics(SprintStatus.COMPLETED);
    for (Sprint sprint : missing) {
      recalculate(
          sprint.getId(),
          sprint.getWorkspaceId(),
          sprint.getProjectId(),
          sprint.getName(),
          sprint.getCompletedAt());
    }
    return missing.size();
  }

  private void recalculate(
      UUID sprintId, UUID workspaceId, UUID projectId, String sprintName, Instant completedAt) {
    SprintSnapshot snapshot = snapshotRepository.snapshotAt(projectId, sprintId, completedAt);
    SprintAnalytics analytics =
        sprintAnalyticsRepository
            .findById(sprintId)
            .orElseGet(() -> SprintAnalytics.create(sprintId, workspaceId, projectId));
    analytics.recalculate(sprintName, completedAt, snapshot, Instant.now());
    sprintAnalyticsRepository.save(analytics);
  }
}
