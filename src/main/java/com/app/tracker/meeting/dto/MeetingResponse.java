package com.app.tracker.meeting.dto;

import com.app.tracker.meeting.model.Meeting;
import com.app.tracker.meeting.service.MeetingOccurrenceCalculator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record MeetingResponse(
    UUID id,
    String title,
    String description,
    String meetingUrl,
    LocalDate startDate,
    LocalTime startTime,
    int durationMinutes,
    String frequency,
    int intervalCount,
    List<String> byWeekday,
    LocalDate untilDate,
    Integer occurrenceCount,
    Integer reminderMinutesBefore,
    boolean standupEnabled,
    UUID createdBy,
    Instant createdAt) {

  /** DependencySummary ile AYNI EI_EXPOSE_REP savunması: List.copyOf. */
  public MeetingResponse {
    byWeekday = List.copyOf(byWeekday);
  }

  public static MeetingResponse from(Meeting meeting) {
    return new MeetingResponse(
        meeting.getId(),
        meeting.getTitle(),
        meeting.getDescription(),
        meeting.getMeetingUrl(),
        meeting.getStartDate(),
        meeting.getStartTime(),
        meeting.getDurationMinutes(),
        meeting.getFrequency(),
        meeting.getIntervalCount(),
        MeetingOccurrenceCalculator.weekdayCodes(meeting.getByWeekday()),
        meeting.getUntilDate(),
        meeting.getOccurrenceCount(),
        meeting.getReminderMinutesBefore(),
        meeting.isStandupEnabled(),
        meeting.getCreatedBy(),
        meeting.getCreatedAt());
  }
}
