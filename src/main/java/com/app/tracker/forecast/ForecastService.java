package com.app.tracker.forecast;

import com.app.tracker.analytics.repository.ProjectMetricsRepository;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.repository.SprintRepository;
import com.app.tracker.task.repository.TaskRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dalga 2.2 (ADR-0013) — Monte Carlo tahmininin okuma servisi. Cache-aside: {@link
 * ForecastCacheService}'te bulunamazsa hesaplanir ve yazilir; {@code CycleTimeConsumer} bir Done
 * gecisi/silme isledikten SONRA {@code evictProject} cagirarak onbellegi temizler.
 *
 * <p>Ornek pencere 12 hafta (84 gun) — {@code AnalyticsController}'daki Throughput varsayilan
 * pencereyle (12 hafta) AYNI, projenin genel temposunu yansitmasi icin yeterince genis ama eski bir
 * surecin etkisini tasimayacak kadar dar.
 */
@Service
public class ForecastService {

  static final int SAMPLE_WINDOW_DAYS = 84;

  private final ProjectRepository projectRepository;
  private final SprintRepository sprintRepository;
  private final TaskRepository taskRepository;
  private final ProjectMetricsRepository projectMetricsRepository;
  private final ForecastCacheService cacheService;
  private final Clock clock;

  public ForecastService(
      ProjectRepository projectRepository,
      SprintRepository sprintRepository,
      TaskRepository taskRepository,
      ProjectMetricsRepository projectMetricsRepository,
      ForecastCacheService cacheService,
      Clock clock) {
    this.projectRepository = projectRepository;
    this.sprintRepository = sprintRepository;
    this.taskRepository = taskRepository;
    this.projectMetricsRepository = projectMetricsRepository;
    this.cacheService = cacheService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ForecastResponse projectBacklog(UUID projectId) {
    Project project =
        projectRepository
            .findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Proje bulunamadi."));
    return cacheService
        .getProject(project.getWorkspaceId(), projectId)
        .orElseGet(
            () -> {
              ForecastResponse computed = computeProjectForecast(project);
              cacheService.putProject(project.getWorkspaceId(), projectId, computed);
              return computed;
            });
  }

  @Transactional(readOnly = true)
  public ForecastResponse sprint(UUID sprintId) {
    Sprint sprint =
        sprintRepository
            .findById(sprintId)
            .orElseThrow(() -> new ResourceNotFoundException("Sprint bulunamadi."));
    return cacheService
        .getSprint(sprint.getWorkspaceId(), sprint.getProjectId(), sprintId)
        .orElseGet(
            () -> {
              ForecastResponse computed = computeSprintForecast(sprint);
              cacheService.putSprint(
                  sprint.getWorkspaceId(), sprint.getProjectId(), sprintId, computed);
              return computed;
            });
  }

  private ForecastResponse computeProjectForecast(Project project) {
    LocalDate today = LocalDate.now(clock);
    long remaining = taskRepository.countRemainingByProjectId(project.getId());
    List<Long> samples = dailySamples(project.getId(), today);
    long seed = seedFor(project.getId(), today);
    return MonteCarloForecaster.forecastCompletion(samples, remaining, today, seed)
        .map(f -> toResponse(f, remaining, null, null))
        .orElseGet(() -> ForecastResponse.unavailable(remaining));
  }

  private ForecastResponse computeSprintForecast(Sprint sprint) {
    LocalDate today = LocalDate.now(clock);
    long remaining = taskRepository.countRemainingBySprintId(sprint.getId());
    List<Long> samples = dailySamples(sprint.getProjectId(), today);
    long seed = seedFor(sprint.getId(), today);
    LocalDate endDate = sprint.getEndDate();
    int daysAvailable = (int) Math.max(0, ChronoUnit.DAYS.between(today, endDate));

    Optional<MonteCarloForecaster.CompletionForecast> completion =
        MonteCarloForecaster.forecastCompletion(samples, remaining, today, seed);
    Optional<Double> probability =
        MonteCarloForecaster.probabilityWithinDays(samples, remaining, daysAvailable, seed);
    if (completion.isEmpty() || probability.isEmpty()) {
      return ForecastResponse.unavailable(remaining);
    }
    return toResponse(completion.get(), remaining, endDate, probability.get());
  }

  private List<Long> dailySamples(UUID projectId, LocalDate today) {
    return projectMetricsRepository.dailyCompletedTaskCounts(
        projectId, today.minusDays(SAMPLE_WINDOW_DAYS - 1L), today);
  }

  private static ForecastResponse toResponse(
      MonteCarloForecaster.CompletionForecast forecast,
      long remaining,
      LocalDate targetDate,
      Double probabilityByTargetDate) {
    return new ForecastResponse(
        true,
        forecast.sampleSize(),
        remaining,
        forecast.p50(),
        forecast.p85(),
        forecast.p95(),
        targetDate,
        probabilityByTargetDate);
  }

  /** Ayni gun icinde ayni kimlik icin deterministik (test edilebilir), gunler arasi degisir. */
  private static long seedFor(UUID id, LocalDate today) {
    return Objects.hash(id, today);
  }
}
