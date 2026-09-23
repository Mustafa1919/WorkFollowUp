package com.app.tracker.meeting.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Tekrarlanan toplanti serisi (V20__meetings.sql). RLS'e tabidir. Occurrence'lar DB'de satir olarak
 * SAKLANMAZ — {@link com.app.tracker.meeting.service.MeetingOccurrenceCalculator} bu entity'nin
 * alanlarindan anlik hesaplar; duzenleme/silme her zaman SERI genelini etkiler (v1 kapsam karari,
 * bkz. Hedefler.md).
 */
@Entity
@Table(name = "meetings")
@Getter
@NoArgsConstructor
public class Meeting {

  @Id private UUID id;

  private UUID workspaceId;

  private String title;

  private String description;

  private String meetingUrl;

  private LocalDate startDate;

  private LocalTime startTime;

  private int durationMinutes;

  /** {@link MeetingFrequency} degerlerinden biri. */
  private String frequency;

  private int intervalCount;

  /** 'MON,WED,FRI' CSV; yalniz {@code frequency == WEEKLY} icin anlamli. */
  private String byWeekday;

  private LocalDate untilDate;

  private Integer occurrenceCount;

  /** {@code null} = hatirlatma yok. */
  private Integer reminderMinutesBefore;

  private UUID createdBy;

  private Instant createdAt;

  public static Meeting of(
      UUID id,
      UUID workspaceId,
      String title,
      String description,
      String meetingUrl,
      LocalDate startDate,
      LocalTime startTime,
      int durationMinutes,
      String frequency,
      int intervalCount,
      String byWeekday,
      LocalDate untilDate,
      Integer occurrenceCount,
      Integer reminderMinutesBefore,
      UUID createdBy) {
    Meeting meeting = new Meeting();
    meeting.id = id;
    meeting.workspaceId = workspaceId;
    meeting.createdBy = createdBy;
    meeting.createdAt = Instant.now();
    meeting.apply(
        title,
        description,
        meetingUrl,
        startDate,
        startTime,
        durationMinutes,
        frequency,
        intervalCount,
        byWeekday,
        untilDate,
        occurrenceCount,
        reminderMinutesBefore);
    return meeting;
  }

  public void apply(
      String title,
      String description,
      String meetingUrl,
      LocalDate startDate,
      LocalTime startTime,
      int durationMinutes,
      String frequency,
      int intervalCount,
      String byWeekday,
      LocalDate untilDate,
      Integer occurrenceCount,
      Integer reminderMinutesBefore) {
    this.title = title;
    this.description = description;
    this.meetingUrl = meetingUrl;
    this.startDate = startDate;
    this.startTime = startTime;
    this.durationMinutes = durationMinutes;
    this.frequency = frequency;
    this.intervalCount = intervalCount;
    this.byWeekday = byWeekday;
    this.untilDate = untilDate;
    this.occurrenceCount = occurrenceCount;
    this.reminderMinutesBefore = reminderMinutesBefore;
  }
}
