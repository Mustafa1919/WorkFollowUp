package com.app.tracker.analytics.service;

import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 1.1, kural 1 — "zamanlanmis bir yeniden hesaplama job'i da
 * guvence olarak gece kosar". Olay yolu (VelocityConsumer) kayip/gecikmeli olabilir; bu job
 * tamamlanmis ama {@code sprint_analytics}'te karsiligi olmayan sprint'leri tamamlar.
 *
 * <p>Yalnizca EKSIK olanlari hesaplar; mevcut satirlari tekrar hesaplamaz (sprint metrikleri
 * degismezdir, bkz. {@code SprintAnalytics}). Her workspace ayri transaction'dadir: biri patlarsa
 * digerleri etkilenmez. Ayni anda birden fazla pod kosarsa isler cakisabilir ama sonuc
 * deterministik oldugundan zararsizdir (son yazan ayni degeri yazar).
 */
@Component
@Profile("!migrate")
public class SprintAnalyticsReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(SprintAnalyticsReconciliationJob.class);

  private final WorkspaceService workspaceService;
  private final TenantExecutor tenantExecutor;
  private final VelocityProjector velocityProjector;

  public SprintAnalyticsReconciliationJob(
      WorkspaceService workspaceService,
      TenantExecutor tenantExecutor,
      VelocityProjector velocityProjector) {
    this.workspaceService = workspaceService;
    this.tenantExecutor = tenantExecutor;
    this.velocityProjector = velocityProjector;
  }

  @Scheduled(cron = "0 45 3 * * *")
  public void reconcile() {
    List<UUID> workspaceIds = workspaceService.findAllWorkspaceIds();
    int total = 0;
    for (UUID workspaceId : workspaceIds) {
      try {
        total += tenantExecutor.runAs(workspaceId, velocityProjector::reconcileMissing);
      } catch (RuntimeException e) {
        log.error("sprint_analytics uzlastirmasi basarisiz (workspace={}).", workspaceId, e);
      }
    }
    if (total > 0) {
      log.info("sprint_analytics uzlastirmasi: {} eksik sprint hesaplandi.", total);
    }
  }
}
