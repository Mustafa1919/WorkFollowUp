package com.app.tracker.telemetry.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Sinir (50): tek istekte asiri buyuk bir batch'in Kafka produce dongusunu bloklamasini onler. */
public record UsageBatchRequest(@NotEmpty @Size(max = 50) @Valid List<FeatureUsageEvent> events) {

  public UsageBatchRequest {
    events = events == null ? null : List.copyOf(events);
  }
}
