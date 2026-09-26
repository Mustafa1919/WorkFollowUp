package com.app.tracker.timemachine;

import com.app.tracker.timemachine.dto.TaskSnapshotResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0017 — RetroController ile AYNI ilke: okuma (gecmis kesit goruntuleme) analitik/aktivite
 * okumasi gibi rol siniri tasimaz, TUM workspace uyeleri gorebilir.
 */
@RestController
public class TimeMachineController {

  private final TimeMachineService timeMachineService;

  public TimeMachineController(TimeMachineService timeMachineService) {
    this.timeMachineService = timeMachineService;
  }

  /**
   * {@code cutoff}: ISO-8601 anlik ("2026-09-26T10:00:00Z"), {@code Instant.parse} ile
   * ayristirilir.
   */
  @GetMapping("/api/v1/projects/{projectId}/tasks/snapshot")
  public List<TaskSnapshotResponse> boardAt(
      @PathVariable UUID projectId, @RequestParam Instant cutoff) {
    return timeMachineService.boardAt(projectId, cutoff);
  }
}
