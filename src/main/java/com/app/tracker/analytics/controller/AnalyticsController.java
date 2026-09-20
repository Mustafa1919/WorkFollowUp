package com.app.tracker.analytics.controller;

import com.app.tracker.analytics.dto.CycleTimeResponse;
import com.app.tracker.analytics.dto.ThroughputResponse;
import com.app.tracker.analytics.dto.VelocityResponse;
import com.app.tracker.analytics.service.AnalyticsQueryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 1 / 1.1 — analitik okuma API'si. Rol siniri yoktur: okuma
 * endpoint'leri gibi (bkz. TaskController.list) workspace uyeligi yeterlidir; tenant izolasyonu RLS
 * ile saglanir. Dashboard, projenin calisma tarzina gore Velocity (sprint'li) veya Throughput
 * (Kanban) gosterir; ikisi de her projede sorgulanabilir.
 */
@RestController
public class AnalyticsController {

  static final int MAX_SPRINTS = 20;
  static final int MAX_WEEKS = 52;
  static final int MAX_DAYS = 365;

  private final AnalyticsQueryService analyticsQueryService;

  public AnalyticsController(AnalyticsQueryService analyticsQueryService) {
    this.analyticsQueryService = analyticsQueryService;
  }

  @GetMapping("/api/v1/projects/{projectId}/analytics/velocity")
  public VelocityResponse velocity(
      @PathVariable UUID projectId, @RequestParam(defaultValue = "5") int sprints) {
    return analyticsQueryService.velocity(projectId, bounded(sprints, MAX_SPRINTS));
  }

  @GetMapping("/api/v1/projects/{projectId}/analytics/throughput")
  public ThroughputResponse throughput(
      @PathVariable UUID projectId, @RequestParam(defaultValue = "12") int weeks) {
    return analyticsQueryService.throughput(projectId, bounded(weeks, MAX_WEEKS));
  }

  @GetMapping("/api/v1/projects/{projectId}/analytics/cycle-time")
  public CycleTimeResponse cycleTime(
      @PathVariable UUID projectId, @RequestParam(defaultValue = "30") int days) {
    return analyticsQueryService.cycleTime(projectId, bounded(days, MAX_DAYS));
  }

  private static int bounded(int value, int max) {
    return Math.min(Math.max(value, 1), max);
  }
}
