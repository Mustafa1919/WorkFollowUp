package com.app.tracker.telemetry;

import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.telemetry.dto.ErrorReportRequest;
import com.app.tracker.telemetry.dto.FeatureUsageEvent;
import com.app.tracker.telemetry.dto.UsageBatchRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dalga 4 -- urun kullanim + hata telemetrisi girisi. Ikisi de {@link TelemetryEventPublisher}
 * uzerinden Kafka'ya best-effort produce eder (senkron DB yazimi YOK, bu yuzden 202 Accepted --
 * istegin kabul edildigini, islendigini degil). {@code workspaceId} null olabilir (ornegin
 * workspace secilmeden once olusan bir frontend hatasi) -- publisher bunu zaten null-safe isler.
 */
@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

  private final TelemetryEventPublisher publisher;

  public TelemetryController(TelemetryEventPublisher publisher) {
    this.publisher = publisher;
  }

  @PostMapping("/usage")
  public ResponseEntity<Void> usage(@Valid @RequestBody UsageBatchRequest request) {
    UUID workspaceId = TenantContext.getWorkspaceId();
    UUID userId = CurrentUser.id();
    Instant now = Instant.now();
    for (FeatureUsageEvent event : request.events()) {
      publisher.publishFeatureUsed(workspaceId, userId, event.feature(), event.action(), now);
    }
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/errors")
  public ResponseEntity<Void> error(@Valid @RequestBody ErrorReportRequest request) {
    UUID workspaceId = TenantContext.getWorkspaceId();
    UUID userId = CurrentUser.id();
    publisher.publishError(
        workspaceId,
        userId,
        "frontend",
        request.errorType(),
        request.message(),
        request.path(),
        Instant.now());
    return ResponseEntity.accepted().build();
  }
}
