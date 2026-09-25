package com.app.tracker.retro;

import com.app.tracker.analytics.model.SprintAnalytics;
import com.app.tracker.analytics.repository.ProjectMetricsRepository;
import com.app.tracker.analytics.repository.SprintAnalyticsRepository;
import com.app.tracker.analytics.repository.SprintSnapshotRepository;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.forecast.MonteCarloForecaster;
import com.app.tracker.retro.RetroRepository.CycleTimeOutlier;
import com.app.tracker.retro.RetroRepository.LongestOpenBlocker;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.model.SprintStatus;
import com.app.tracker.sprint.repository.SprintRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dalga 2.4 (ADR-0015) — veriye dayali retrospektif. Yalniz TAMAMLANMIS sprint'ler icin anlamlidir;
 * gerceklesen sayilar {@code sprint_analytics}'ten (Velocity ile AYNI kaynak, celismez),
 * plan/eklenen /cikarilan iki farkli kesitin ({@code startedAt}/{@code completedAt}) uyelik
 * KARSILASTIRMASINDAN gelir.
 */
@Service
public class RetroService {

  private static final int CYCLE_TIME_OUTLIER_LIMIT = 3;
  private static final int FORECAST_SAMPLE_WINDOW_DAYS = 84;

  private final SprintRepository sprintRepository;
  private final SprintAnalyticsRepository sprintAnalyticsRepository;
  private final SprintSnapshotRepository snapshotRepository;
  private final RetroRepository retroRepository;
  private final ProjectMetricsRepository projectMetricsRepository;

  public RetroService(
      SprintRepository sprintRepository,
      SprintAnalyticsRepository sprintAnalyticsRepository,
      SprintSnapshotRepository snapshotRepository,
      RetroRepository retroRepository,
      ProjectMetricsRepository projectMetricsRepository) {
    this.sprintRepository = sprintRepository;
    this.sprintAnalyticsRepository = sprintAnalyticsRepository;
    this.snapshotRepository = snapshotRepository;
    this.retroRepository = retroRepository;
    this.projectMetricsRepository = projectMetricsRepository;
  }

  @Transactional(readOnly = true)
  public RetroResponse build(UUID sprintId) {
    Sprint sprint =
        sprintRepository
            .findById(sprintId)
            .orElseThrow(() -> new ResourceNotFoundException("Sprint bulunamadi."));
    if (!SprintStatus.COMPLETED.equals(sprint.getStatus())) {
      throw new BusinessRuleException("Retro yalniz tamamlanmis sprint'ler icin gorulebilir.");
    }
    SprintAnalytics analytics =
        sprintAnalyticsRepository
            .findById(sprintId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Bu sprint icin analitik henuz hesaplanmadi."));

    List<UUID> endMembers =
        snapshotRepository.membersAt(sprint.getProjectId(), sprintId, sprint.getCompletedAt());
    List<RetroTaskRef> spillover = retroRepository.notDone(endMembers);
    List<CycleTimeOutlier> outliers =
        retroRepository.cycleTimeOutliers(endMembers, CYCLE_TIME_OUTLIER_LIMIT);
    Optional<LongestOpenBlocker> blocker = retroRepository.longestOpenBlocker(endMembers);

    boolean planAvailable =
        analytics.getCommittedAtStartTasks() != null && sprint.getStartedAt() != null;
    List<RetroTaskRef> added = List.of();
    List<RetroTaskRef> removed = List.of();
    Double forecastProbability = null;
    if (planAvailable) {
      List<UUID> startMembers =
          snapshotRepository.membersAt(sprint.getProjectId(), sprintId, sprint.getStartedAt());
      Set<UUID> startSet = new HashSet<>(startMembers);
      Set<UUID> endSet = new HashSet<>(endMembers);
      added = retroRepository.taskRefs(diff(endSet, startSet));
      removed = retroRepository.taskRefs(diff(startSet, endSet));
      forecastProbability =
          retroactiveForecastProbability(sprint, analytics.getCommittedAtStartTasks());
    }

    return new RetroResponse(
        planAvailable,
        analytics.getCommittedAtStartTasks(),
        analytics.getCommittedAtStartPoints(),
        analytics.getCommittedTasks(),
        analytics.getCommittedPoints(),
        analytics.getCompletedTasks(),
        analytics.getCompletedPoints(),
        added,
        removed,
        spillover,
        outliers,
        blocker.orElse(null),
        forecastProbability);
  }

  /**
   * "Sprint baslarken elimizdeki veriyle bu isin zamaninda bitme ihtimali ne kadardi?" — AYNI
   * {@link MonteCarloForecaster} (ADR-0013), yalniz orneklem penceresi sprint BASLAMADAN ONCEKI
   * gunlerle sinirlanir (gelecegi sizdirmamak icin).
   */
  private Double retroactiveForecastProbability(Sprint sprint, int remainingAtStart) {
    LocalDate startDate = sprint.getStartedAt().atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate sampleTo = startDate.minusDays(1);
    LocalDate sampleFrom = sampleTo.minusDays(FORECAST_SAMPLE_WINDOW_DAYS - 1L);
    List<Long> samples =
        projectMetricsRepository.dailyCompletedTaskCounts(
            sprint.getProjectId(), sampleFrom, sampleTo);
    int daysAvailable = (int) Math.max(0, ChronoUnit.DAYS.between(startDate, sprint.getEndDate()));
    long seed = Objects.hash(sprint.getId(), "retro");
    return MonteCarloForecaster.probabilityWithinDays(
            samples, remainingAtStart, daysAvailable, seed)
        .orElse(null);
  }

  private static List<UUID> diff(Set<UUID> from, Set<UUID> subtract) {
    List<UUID> result = new ArrayList<>(from);
    result.removeAll(subtract);
    return result;
  }
}
