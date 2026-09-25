package com.app.tracker.flow.controller;

import com.app.tracker.flow.dto.AgingWipResponse;
import com.app.tracker.flow.service.AgingWipService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dalga 2.1 — Aging WIP okuma API'si. {@code AnalyticsController} ile AYNI ilke: rol siniri yok,
 * workspace uyeligi yeterli, tenant izolasyonu RLS ile saglanir.
 */
@RestController
public class FlowController {

  private final AgingWipService agingWipService;

  public FlowController(AgingWipService agingWipService) {
    this.agingWipService = agingWipService;
  }

  @GetMapping("/api/v1/projects/{projectId}/flow/aging")
  public AgingWipResponse aging(@PathVariable UUID projectId) {
    return agingWipService.aging(projectId);
  }
}
