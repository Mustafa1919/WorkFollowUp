package com.app.tracker.flow.dto;

import java.util.List;
import java.util.UUID;

/**
 * @param thresholdAvailable false ise ornek yetersiz (10'dan az tamamlanmis gorev); {@code
 *     p85Seconds} ve {@code items} bu durumda anlamli degildir (items bos doner).
 */
public record AgingWipResponse(
    boolean thresholdAvailable, Double p85Seconds, List<AgingTaskEntry> items) {

  public AgingWipResponse {
    items = List.copyOf(items);
  }

  public record AgingTaskEntry(
      UUID taskId, Integer taskNumber, String title, long ageSeconds, int level) {}
}
