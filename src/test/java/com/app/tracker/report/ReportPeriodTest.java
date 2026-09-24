package com.app.tracker.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.report.model.ReportPeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Saf donem aritmetigi — Spring gerekmez ({@code MeetingOccurrenceCalculatorTest} ile ayni desen).
 */
class ReportPeriodTest {

  private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

  @Test
  void yearlyPeriodSpansWholeCalendarYear() {
    ReportPeriod year = new ReportPeriod(2026, null);

    assertEquals(LocalDate.of(2026, 1, 1), year.startDate());
    assertEquals(LocalDate.of(2026, 12, 31), year.endDate());
    assertEquals(LocalDate.of(2027, 1, 1), year.endDateExclusive());
    assertEquals(12, year.monthCount());
    assertEquals("2026", year.label());
  }

  @Test
  void quarterBoundariesFollowCalendarQuarters() {
    assertEquals(LocalDate.of(2026, 1, 1), new ReportPeriod(2026, 1).startDate());
    assertEquals(LocalDate.of(2026, 4, 1), new ReportPeriod(2026, 2).startDate());
    assertEquals(LocalDate.of(2026, 7, 1), new ReportPeriod(2026, 3).startDate());
    assertEquals(LocalDate.of(2026, 10, 1), new ReportPeriod(2026, 4).startDate());
    assertEquals(LocalDate.of(2026, 12, 31), new ReportPeriod(2026, 4).endDate());
    assertEquals(3, new ReportPeriod(2026, 2).monthCount());
    assertEquals("2026-Q3", new ReportPeriod(2026, 3).label());
  }

  /** Donem siniri IS saat diliminde baslar: Q3 Istanbul'da 30 Haziran 21:00 UTC'de acilir. */
  @Test
  void boundariesAreComputedInBusinessZoneNotUtc() {
    ReportPeriod q3 = new ReportPeriod(2026, 3);

    assertEquals(Instant.parse("2026-06-30T21:00:00Z"), q3.startInstant(ISTANBUL));
    assertEquals(Instant.parse("2026-09-30T21:00:00Z"), q3.endInstantExclusive(ISTANBUL));
  }

  @Test
  void previousPeriodRollsBackOverYearBoundary() {
    assertEquals(new ReportPeriod(2026, 2), new ReportPeriod(2026, 3).previous());
    assertEquals(new ReportPeriod(2025, 4), new ReportPeriod(2026, 1).previous());
    assertEquals(new ReportPeriod(2025, null), new ReportPeriod(2026, null).previous());
  }

  @Test
  void invalidPeriodsAreRejected() {
    assertThrows(BusinessRuleException.class, () -> new ReportPeriod(1999, null));
    assertThrows(BusinessRuleException.class, () -> new ReportPeriod(3000, null));
    assertThrows(BusinessRuleException.class, () -> new ReportPeriod(2026, 0));
    assertThrows(BusinessRuleException.class, () -> new ReportPeriod(2026, 5));
  }
}
