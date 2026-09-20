package com.app.tracker.analytics.service;

import com.app.tracker.analytics.dto.CycleTimeResponse;
import com.app.tracker.analytics.dto.SprintVelocityResponse;
import com.app.tracker.analytics.dto.ThroughputResponse;
import com.app.tracker.analytics.dto.ThroughputResponse.WeeklyThroughput;
import com.app.tracker.analytics.dto.VelocityResponse;
import com.app.tracker.analytics.model.SprintAnalytics;
import com.app.tracker.analytics.repository.ProjectMetricsRepository;
import com.app.tracker.analytics.repository.ProjectMetricsRepository.CycleTimeStats;
import com.app.tracker.analytics.repository.SprintAnalyticsRepository;
import com.app.tracker.core.datasource.ReadReplica;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.project.repository.ProjectRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 1 (CQRS okuma tarafi) — analitik GET istekleri. Sinif {@link
 * ReadReplica} ile isaretlidir: butun okumalar read DataSource'a gider ve replikasyon gecikmesine
 * TOLERANSLIDIR (analitik sonuclar zaten olay islendikce eventual consistent olusur).
 *
 * <p>Proje varligi kontrolu RLS'e tabidir: baska tenant'in / var olmayan projesi 404 doner. Bu
 * kontrol de replica'dan okunur; gercek bir replica'da yeni yaratilmis proje kisa sure 404
 * verebilir — bos analitik sonuc zaten anlamsiz oldugu icin kabul edilmis bir takastir.
 */
@Service
@ReadReplica
public class AnalyticsQueryService {

  private final ProjectRepository projectRepository;
  private final SprintAnalyticsRepository sprintAnalyticsRepository;
  private final ProjectMetricsRepository projectMetricsRepository;

  public AnalyticsQueryService(
      ProjectRepository projectRepository,
      SprintAnalyticsRepository sprintAnalyticsRepository,
      ProjectMetricsRepository projectMetricsRepository) {
    this.projectRepository = projectRepository;
    this.sprintAnalyticsRepository = sprintAnalyticsRepository;
    this.projectMetricsRepository = projectMetricsRepository;
  }

  /**
   * Son {@code sprintCount} kapanan sprint ve hareketli ortalama velocity (tamamlanan puanlarin
   * ortalamasi, 1 ondalik).
   */
  @Transactional(readOnly = true)
  public VelocityResponse velocity(UUID projectId, int sprintCount) {
    requireProject(projectId);
    List<SprintAnalytics> recent =
        sprintAnalyticsRepository.findByProjectIdOrderByCompletedAtDesc(
            projectId, PageRequest.ofSize(sprintCount));
    Double average =
        recent.isEmpty()
            ? null
            : BigDecimal.valueOf(
                    recent.stream().mapToLong(SprintAnalytics::getCompletedPoints).sum())
                .divide(BigDecimal.valueOf(recent.size()), 1, RoundingMode.HALF_UP)
                .doubleValue();
    return new VelocityResponse(
        recent.stream().map(SprintVelocityResponse::from).toList(), average);
  }

  /** Son {@code weekCount} ISO haftasi (icinde bulunulan hafta dahil), bos haftalar 0. */
  @Transactional(readOnly = true)
  public ThroughputResponse throughput(UUID projectId, int weekCount) {
    requireProject(projectId);
    LocalDate currentWeek =
        LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate firstWeek = currentWeek.minusWeeks(weekCount - 1L);
    Map<LocalDate, Long> counts =
        projectMetricsRepository.weeklyCompletedTasks(
            projectId, firstWeek.atStartOfDay(ZoneOffset.UTC).toInstant());

    List<WeeklyThroughput> weeks = new ArrayList<>(weekCount);
    for (LocalDate week = firstWeek; !week.isAfter(currentWeek); week = week.plusWeeks(1)) {
      weeks.add(new WeeklyThroughput(week, counts.getOrDefault(week, 0L)));
    }
    return new ThroughputResponse(weeks);
  }

  @Transactional(readOnly = true)
  public CycleTimeResponse cycleTime(UUID projectId, int days) {
    requireProject(projectId);
    CycleTimeStats stats =
        projectMetricsRepository.cycleTimeStats(
            projectId, Instant.now().minus(days, ChronoUnit.DAYS));
    return new CycleTimeResponse(
        days, stats.sampleSize(), stats.average(), stats.median(), stats.p85(), stats.p95());
  }

  private void requireProject(UUID projectId) {
    if (!projectRepository.existsById(projectId)) {
      throw new ResourceNotFoundException("Proje bulunamadi.");
    }
  }
}
