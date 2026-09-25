package com.app.tracker.flow.service;

import com.app.tracker.analytics.model.TaskAnalytics;
import com.app.tracker.analytics.repository.ProjectMetricsRepository;
import com.app.tracker.analytics.repository.ProjectMetricsRepository.CycleTimeStats;
import com.app.tracker.analytics.repository.TaskAnalyticsRepository;
import com.app.tracker.core.datasource.ReadReplica;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.flow.AgingWipLevels;
import com.app.tracker.flow.dto.AgingWipResponse;
import com.app.tracker.flow.dto.AgingWipResponse.AgingTaskEntry;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dalga 2.1 — Aging WIP okuma tarafi. {@code AnalyticsQueryService} ile AYNI desen: {@link
 * ReadReplica}, proje varligi replica'dan kontrol edilir. Esik hesabi {@link AgingWipLevels}'ta
 * (job ile PAYLASILAN saf fonksiyon), boylece rozet ile bildirim seviyesi hep AYNI tanima dayanir.
 */
@Service
@ReadReplica
public class AgingWipService {

  private final ProjectRepository projectRepository;
  private final ProjectMetricsRepository projectMetricsRepository;
  private final TaskAnalyticsRepository taskAnalyticsRepository;
  private final TaskRepository taskRepository;

  public AgingWipService(
      ProjectRepository projectRepository,
      ProjectMetricsRepository projectMetricsRepository,
      TaskAnalyticsRepository taskAnalyticsRepository,
      TaskRepository taskRepository) {
    this.projectRepository = projectRepository;
    this.projectMetricsRepository = projectMetricsRepository;
    this.taskAnalyticsRepository = taskAnalyticsRepository;
    this.taskRepository = taskRepository;
  }

  @Transactional(readOnly = true)
  public AgingWipResponse aging(UUID projectId) {
    if (!projectRepository.existsById(projectId)) {
      throw new ResourceNotFoundException("Proje bulunamadi.");
    }
    CycleTimeStats stats = projectMetricsRepository.cycleTimeStats(projectId, Instant.EPOCH);
    if (stats.sampleSize() < AgingWipLevels.MIN_SAMPLE_SIZE || stats.p85() == null) {
      return new AgingWipResponse(false, null, List.of());
    }
    double p85Seconds = stats.p85();
    List<TaskAnalytics> open = taskAnalyticsRepository.findOpenByProjectId(projectId);
    if (open.isEmpty()) {
      return new AgingWipResponse(true, p85Seconds, List.of());
    }
    Instant now = Instant.now();
    Map<UUID, Task> tasksById =
        taskRepository.findAllById(open.stream().map(TaskAnalytics::getTaskId).toList()).stream()
            .collect(java.util.stream.Collectors.toMap(Task::getId, Function.identity()));
    List<AgingTaskEntry> items =
        open.stream()
            .filter(a -> tasksById.containsKey(a.getTaskId()))
            .map(
                a -> {
                  Task task = tasksById.get(a.getTaskId());
                  long ageSeconds =
                      now.getEpochSecond() - a.getFirstInProgressAt().getEpochSecond();
                  int level = AgingWipLevels.levelFor(ageSeconds, p85Seconds);
                  return new AgingTaskEntry(
                      task.getId(), task.getTaskNumber(), task.getTitle(), ageSeconds, level);
                })
            .sorted(Comparator.comparingLong(AgingTaskEntry::ageSeconds).reversed())
            .toList();
    return new AgingWipResponse(true, p85Seconds, items);
  }
}
