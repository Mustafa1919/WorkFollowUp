package com.app.tracker.core.kafka;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 3.3, madde 3 — DLT replay yonetici endpoint'i. Workspace'e ozgu
 * degildir (bir workspace'in degil, sistemin Kafka altyapisinin ustunde calisir), bu yuzden
 * {@code @securityGuard.hasCurrentWorkspaceRole} DEGIL, global {@code SYSTEM_ADMIN} yetkisi
 * (SystemAdminProperties) gerektirir.
 */
@RestController
public class KafkaReplayController {

  private final KafkaReplayService replayService;

  public KafkaReplayController(KafkaReplayService replayService) {
    this.replayService = replayService;
  }

  @PostMapping("/api/v1/admin/kafka/replay")
  @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
  public KafkaReplayService.ReplayResult replay(@Valid @RequestBody ReplayRequest request) {
    return replayService.replay(
        request.dltTopic(), request.partition(), request.fromOffset(), request.toOffset());
  }
}
