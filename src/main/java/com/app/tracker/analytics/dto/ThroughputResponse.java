package com.app.tracker.analytics.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Haftalik tamamlanan gorev sayisi. {@code weeks} eskiden yeniye siralidir, hic tamamlanma olmayan
 * haftalar da {@code 0} ile yer alir (grafik icin bosluksuz seri).
 */
public record ThroughputResponse(List<WeeklyThroughput> weeks) {

  public ThroughputResponse {
    weeks = List.copyOf(weeks);
  }

  /** {@code weekStart}: ISO haftasinin Pazartesi'si (UTC). */
  public record WeeklyThroughput(LocalDate weekStart, long completedTasks) {}
}
