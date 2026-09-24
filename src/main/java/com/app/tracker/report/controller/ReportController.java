package com.app.tracker.report.controller;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.report.dto.PeriodReportResponse;
import com.app.tracker.report.model.ReportPeriod;
import com.app.tracker.report.service.PeriodReportService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Donemsel rapor okuma API'si. {@code AnalyticsController} ile ayni yetki deseni: rol siniri yok,
 * workspace uyeligi yeterli; tenant izolasyonu RLS ile saglanir.
 */
@RestController
public class ReportController {

  /**
   * Proje suzgeci ust siniri. Suzgec SQL'e {@code IN (?,?,...)} olarak acildigi icin sinirsiz liste
   * hem sorguyu hem plan cache'ini sisirirdi; workspace'te bundan fazla proje varsa suzgec zaten
   * anlamsizlasir (suzgecsiz = tumu).
   */
  static final int MAX_PROJECT_FILTER = 100;

  private final PeriodReportService periodReportService;

  public ReportController(PeriodReportService periodReportService) {
    this.periodReportService = periodReportService;
  }

  /**
   * @param quarter verilmezse TUM YIL raporlanir
   * @param projectIds verilmezse workspace'in tum projeleri
   */
  @GetMapping("/api/v1/reports/period")
  public PeriodReportResponse period(
      @RequestParam int year,
      @RequestParam(required = false) Integer quarter,
      @RequestParam(required = false) List<UUID> projectIds) {
    if (projectIds != null && projectIds.size() > MAX_PROJECT_FILTER) {
      throw new BusinessRuleException(
          "En fazla " + MAX_PROJECT_FILTER + " proje suzgeci verilebilir.");
    }
    return periodReportService.report(new ReportPeriod(year, quarter), projectIds);
  }
}
