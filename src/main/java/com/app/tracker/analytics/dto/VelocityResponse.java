package com.app.tracker.analytics.dto;

import java.util.List;

/**
 * Son kapanan sprint'ler (en yeni basta) ve bunlarin hareketli ortalama velocity'si (tek sprint
 * yaniltici oldugu icin, bkz. PHASE_3 Bolum 1.1). Hic kapanmis sprint yoksa {@code averageVelocity}
 * {@code null}'dur — Kanban projelerinde throughput endpoint'i kullanilir.
 */
public record VelocityResponse(List<SprintVelocityResponse> sprints, Double averageVelocity) {

  public VelocityResponse {
    sprints = List.copyOf(sprints);
  }
}
