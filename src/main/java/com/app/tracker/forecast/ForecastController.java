package com.app.tracker.forecast;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dalga 2.2 — Monte Carlo tahmin okuma API'si. Analitik/Aging WIP okuma uclariyla AYNI ilke: rol
 * siniri yok, workspace uyeligi yeterli.
 */
@RestController
public class ForecastController {

  private final ForecastService forecastService;

  public ForecastController(ForecastService forecastService) {
    this.forecastService = forecastService;
  }

  @GetMapping("/api/v1/projects/{projectId}/forecast/backlog")
  public ForecastResponse projectBacklog(@PathVariable UUID projectId) {
    return forecastService.projectBacklog(projectId);
  }

  @GetMapping("/api/v1/sprints/{sprintId}/forecast")
  public ForecastResponse sprint(@PathVariable UUID sprintId) {
    return forecastService.sprint(sprintId);
  }
}
