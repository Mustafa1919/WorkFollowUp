package com.app.tracker.goal.dto;

import com.app.tracker.goal.model.Goal;
import com.app.tracker.goal.model.GoalMetricType;
import java.util.UUID;

/**
 * CRUD cevabi. Ilerleme BILEREK yok: otomatik metriklerde ilerleme donemin read model sorgusunu
 * gerektirir ve raporla birlikte tek kesitte doner (bkz. {@code PeriodReportResponse.GoalProgress})
 * — burada da dondurmek, iki farkli anda hesaplanmis iki farkli sayi uretebilirdi.
 */
public record GoalResponse(
    UUID id,
    String title,
    GoalMetricType metricType,
    long targetValue,
    Long manualValue,
    int year,
    Integer quarter,
    UUID projectId) {

  public static GoalResponse from(Goal goal) {
    return new GoalResponse(
        goal.getId(),
        goal.getTitle(),
        goal.getMetricType(),
        goal.getTargetValue(),
        goal.getManualValue(),
        goal.getPeriodYear(),
        goal.getPeriodQuarter() == null ? null : goal.getPeriodQuarter().intValue(),
        goal.getProjectId());
  }
}
