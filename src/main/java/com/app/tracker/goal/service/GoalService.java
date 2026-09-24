package com.app.tracker.goal.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.goal.model.Goal;
import com.app.tracker.goal.model.GoalMetricType;
import com.app.tracker.goal.repository.GoalRepository;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.report.model.ReportPeriod;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Donem hedefleri (V21__goals.sql) — raporun ileriye donuk tarafi. Tek seviye, dar kapsam: OKR
 * hiyerarsisi yok (bkz. migration ust yorumu).
 *
 * <p>Bilerek outbox'a/task_events'e HICBIR olay yazilmaz: hedef degisikligini tuketen bir worker
 * yok ve hicbir analitigi etkilemiyor — TagService'in {@code TASK_TAGS_CHANGED} gerekcesinin tersi
 * yonde ayni muhakeme (orada WebSocket fan-out tuketicisi vardi, burada yok).
 */
@Service
public class GoalService {

  private static final int MAX_GOALS_PER_PERIOD = 50;

  private final GoalRepository goalRepository;
  private final ProjectRepository projectRepository;

  public GoalService(GoalRepository goalRepository, ProjectRepository projectRepository) {
    this.goalRepository = goalRepository;
    this.projectRepository = projectRepository;
  }

  @Transactional(readOnly = true)
  public List<Goal> listByPeriod(ReportPeriod period) {
    short year = (short) period.year();
    return period.quarter() == null
        ? goalRepository.findByPeriodYearAndPeriodQuarterIsNullOrderByCreatedAtAsc(year)
        : goalRepository.findByPeriodYearAndPeriodQuarterOrderByCreatedAtAsc(
            year, period.quarter().shortValue());
  }

  @Transactional
  public Goal create(
      ReportPeriod period,
      String title,
      GoalMetricType metricType,
      long targetValue,
      UUID projectId) {
    UUID workspaceId = requireWorkspace();
    requireProjectIfPresent(projectId);
    requirePositiveTarget(targetValue);
    // Ust sinir: hedef listesi rapor sayfasinda TAMAMEN render edilir ve her biri icin ilerleme
    // hesaplanir; sinirsiz hedef sayfayi da sorguyu da anlamsizca sisirirdi.
    if (listByPeriod(period).size() >= MAX_GOALS_PER_PERIOD) {
      throw new BusinessRuleException(
          "Bir donemde en fazla " + MAX_GOALS_PER_PERIOD + " hedef tanimlanabilir.");
    }
    return goalRepository.save(
        Goal.of(
            UUID.randomUUID(),
            workspaceId,
            projectId,
            period.year(),
            period.quarter(),
            title.trim(),
            metricType,
            targetValue));
  }

  /** Donem ve metrik tipi DEGISMEZ (bkz. {@link Goal#edit}); degisebilen baslik/hedef/kapsam. */
  @Transactional
  public Goal update(UUID goalId, String title, long targetValue, UUID projectId) {
    Goal goal = requireGoal(goalId);
    requireProjectIfPresent(projectId);
    requirePositiveTarget(targetValue);
    goal.edit(title.trim(), targetValue, projectId);
    return goal;
  }

  /** Yalniz {@link GoalMetricType#CUSTOM}: otomatik metrikler read model'den hesaplanir. */
  @Transactional
  public Goal updateProgress(UUID goalId, long value) {
    Goal goal = requireGoal(goalId);
    if (goal.getMetricType() != GoalMetricType.CUSTOM) {
      throw new BusinessRuleException(
          "Bu hedefin ilerlemesi otomatik hesaplanir, elle guncellenemez.");
    }
    if (value < 0) {
      throw new BusinessRuleException("Ilerleme negatif olamaz.");
    }
    goal.updateManualValue(value);
    return goal;
  }

  @Transactional
  public void delete(UUID goalId) {
    goalRepository.delete(requireGoal(goalId));
  }

  private Goal requireGoal(UUID goalId) {
    return goalRepository
        .findById(goalId)
        .orElseThrow(() -> new ResourceNotFoundException("Hedef bulunamadi."));
  }

  private void requireProjectIfPresent(UUID projectId) {
    // RLS sayesinde baska tenant'in projesi de "yok" gorunur.
    if (projectId != null && !projectRepository.existsById(projectId)) {
      throw new ResourceNotFoundException("Proje bulunamadi.");
    }
  }

  private static void requirePositiveTarget(long targetValue) {
    if (targetValue <= 0) {
      throw new BusinessRuleException("Hedef deger sifirdan buyuk olmalidir.");
    }
  }

  private static UUID requireWorkspace() {
    UUID workspaceId = TenantContext.getWorkspaceId();
    if (workspaceId == null) {
      throw new BusinessRuleException("Once bir workspace secmelisiniz (X-Workspace-Id header).");
    }
    return workspaceId;
  }
}
