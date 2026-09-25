package com.app.tracker.meeting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * {@code byWeekday}: {@code ["MON","WED"]} gibi 3 harfli gün kodları, yalnız {@code
 * frequency=WEEKLY} için anlamlıdır (çapraz alan kuralı — servis katmanında doğrulanır, bkz. {@code
 * MeetingService#validate}). {@code untilDate}/{@code occurrenceCount} de aynı şekilde birbirini
 * dışlayan bir çift, DB CHECK ikinci savunma hattıdır.
 */
public record MeetingRequest(
    @NotBlank @Size(max = 200) String title,
    @Size(max = 1000) String description,
    @Size(max = 500) String meetingUrl,
    @NotNull LocalDate startDate,
    @NotNull LocalTime startTime,
    @Min(5) @Max(480) int durationMinutes,
    @NotBlank @Pattern(regexp = "ONCE|DAILY|WEEKLY|MONTHLY") String frequency,
    @Min(1) @Max(52) int intervalCount,
    List<@Pattern(regexp = "MON|TUE|WED|THU|FRI|SAT|SUN") String> byWeekday,
    LocalDate untilDate,
    @Min(1) @Max(366) Integer occurrenceCount,
    @Min(1) @Max(1440) Integer reminderMinutesBefore,
    Boolean standupEnabled) {

  /**
   * DependencySummary ile AYNI EI_EXPOSE_REP savunması: List.copyOf. {@code standupEnabled}
   * BİLEREK primitif {@code boolean} DEĞİL: bu alanı henüz göndermeyen eski bir istemci (veya
   * onu hiç bilmeyen bir entegrasyon) {@code Jackson}'ın kayıp bir zorunlu constructor
   * parametresi için 400 dönmesine yol açardı — diğer opsiyonel alanlar (occurrenceCount,
   * reminderMinutesBefore) gibi null-güvenli tutulur.
   */
  public MeetingRequest {
    byWeekday = byWeekday == null ? null : List.copyOf(byWeekday);
    standupEnabled = standupEnabled != null && standupEnabled;
  }
}
