package com.app.tracker.meeting.dto;

import com.app.tracker.meeting.service.MeetingService.Occurrence;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** Takvim görünümü için düzleştirilmiş occurrence — seri değil, tek bir tekrar. */
public record MeetingOccurrenceResponse(
    UUID meetingId,
    String title,
    String meetingUrl,
    LocalDate date,
    LocalTime startTime,
    int durationMinutes) {

  public static MeetingOccurrenceResponse from(Occurrence occurrence) {
    return new MeetingOccurrenceResponse(
        occurrence.meeting().getId(),
        occurrence.meeting().getTitle(),
        occurrence.meeting().getMeetingUrl(),
        occurrence.date(),
        occurrence.meeting().getStartTime(),
        occurrence.meeting().getDurationMinutes());
  }
}
