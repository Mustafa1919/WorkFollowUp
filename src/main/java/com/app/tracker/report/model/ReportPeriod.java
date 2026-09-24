package com.app.tracker.report.model;

import com.app.tracker.core.exception.BusinessRuleException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Raporun TAKVIM donemi: bir yil ya da bir ceyrek. Kayan pencere ("son 3 ay") BILEREK secilmedi —
 * rapor karsilastirilabilir ve arsivlenebilir olmali: ayni donem raporu iki hafta sonra ayni sonucu
 * vermeli ve gecen ceyrekle kiyaslanabilmeli (kullanici karari, 2026-09-24).
 *
 * <p>Donem sinirlari IS saat diliminde ({@code app.business-time-zone}, bkz. {@link
 * com.app.tracker.core.config.ClockConfig}) hesaplanir: pod UTC'de kossa da "2026 Q3" Istanbul'da 1
 * Temmuz 00:00'da baslar. {@code task_analytics.done_at} ve {@code sprint_analytics.completed_at}
 * {@code TIMESTAMPTZ} oldugu icin karsilastirma dogrudur.
 *
 * @param year takvim yili
 * @param quarter 1-4, ya da {@code null} = tum yil
 */
public record ReportPeriod(int year, Integer quarter) {

  private static final int MIN_YEAR = 2000;
  private static final int MAX_YEAR = 2999;

  public ReportPeriod {
    if (year < MIN_YEAR || year > MAX_YEAR) {
      throw new BusinessRuleException("Gecersiz rapor yili: " + year);
    }
    if (quarter != null && (quarter < 1 || quarter > 4)) {
      throw new BusinessRuleException("Ceyrek 1-4 arasinda olmalidir.");
    }
  }

  /** Donemin ilk gunu (dahil). */
  public LocalDate startDate() {
    return quarter == null
        ? LocalDate.of(year, 1, 1)
        : LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
  }

  /** Donemi TAKIP EDEN ilk gun (haric) — yarim acik aralik kullanilir. */
  public LocalDate endDateExclusive() {
    return quarter == null ? startDate().plusYears(1) : startDate().plusMonths(3);
  }

  /** Donemin son gunu (dahil) — yalniz gosterim icin. */
  public LocalDate endDate() {
    return endDateExclusive().minusDays(1);
  }

  public Instant startInstant(ZoneId zone) {
    return startDate().atStartOfDay(zone).toInstant();
  }

  public Instant endInstantExclusive(ZoneId zone) {
    return endDateExclusive().atStartOfDay(zone).toInstant();
  }

  /**
   * Bir onceki ayni uzunlukta donem (kiyaslama icin): yillikta onceki yil, ceyreklikte onceki
   * ceyrek.
   */
  public ReportPeriod previous() {
    if (quarter == null) {
      return new ReportPeriod(year - 1, null);
    }
    return quarter == 1 ? new ReportPeriod(year - 1, 4) : new ReportPeriod(year, quarter - 1);
  }

  /** Donemdeki ay sayisi (yillik 12, ceyreklik 3). */
  public int monthCount() {
    return quarter == null ? 12 : 3;
  }

  public String label() {
    return quarter == null ? String.valueOf(year) : year + "-Q" + quarter;
  }
}
