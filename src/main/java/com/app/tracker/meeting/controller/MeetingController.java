package com.app.tracker.meeting.controller;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.meeting.dto.MeetingOccurrenceResponse;
import com.app.tracker.meeting.dto.MeetingRequest;
import com.app.tracker.meeting.dto.MeetingResponse;
import com.app.tracker.meeting.model.Meeting;
import com.app.tracker.meeting.service.MeetingOccurrenceCalculator;
import com.app.tracker.meeting.service.MeetingService;
import com.app.tracker.workspace.model.WorkspaceRole;
import jakarta.validation.Valid;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Toplantı planlama (workspace geneli — proje sınırına bağlı değil). {@code TagController} ile aynı
 * yetki deseni: tanım (oluştur/değiştir/sil) ADMIN/MANAGER, okuma her üye.
 */
@RestController
@RequestMapping("/api/v1/meetings")
public class MeetingController {

  /** {@code TaskController.calendar} ile AYNI sınır — 6 haftalık ay ızgarası 42 gün. */
  private static final int MAX_OCCURRENCE_RANGE_DAYS = 62;

  private static final String MANAGE =
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "')";

  private final MeetingService meetingService;

  public MeetingController(MeetingService meetingService) {
    this.meetingService = meetingService;
  }

  @PostMapping
  @PreAuthorize(MANAGE)
  public ResponseEntity<MeetingResponse> create(@Valid @RequestBody MeetingRequest request) {
    Meeting meeting =
        meetingService.create(
            request.title(),
            request.description(),
            request.meetingUrl(),
            request.startDate(),
            request.startTime(),
            request.durationMinutes(),
            request.frequency(),
            request.intervalCount(),
            weekdaySet(request.byWeekday()),
            request.untilDate(),
            request.occurrenceCount(),
            request.reminderMinutesBefore(),
            request.standupEnabled(),
            CurrentUser.id());
    return ResponseEntity.status(HttpStatus.CREATED).body(MeetingResponse.from(meeting));
  }

  @GetMapping
  public List<MeetingResponse> list() {
    return meetingService.list().stream().map(MeetingResponse::from).toList();
  }

  @GetMapping("/occurrences")
  public List<MeetingOccurrenceResponse> occurrences(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    if (ChronoUnit.DAYS.between(from, to) > MAX_OCCURRENCE_RANGE_DAYS) {
      throw new BusinessRuleException(
          "Takvim aralığı en fazla " + MAX_OCCURRENCE_RANGE_DAYS + " gün olabilir.");
    }
    return meetingService.occurrencesInRange(from, to).stream()
        .map(MeetingOccurrenceResponse::from)
        .toList();
  }

  @PutMapping("/{meetingId}")
  @PreAuthorize(MANAGE)
  public MeetingResponse update(
      @PathVariable UUID meetingId, @Valid @RequestBody MeetingRequest request) {
    Meeting meeting =
        meetingService.update(
            meetingId,
            request.title(),
            request.description(),
            request.meetingUrl(),
            request.startDate(),
            request.startTime(),
            request.durationMinutes(),
            request.frequency(),
            request.intervalCount(),
            weekdaySet(request.byWeekday()),
            request.untilDate(),
            request.occurrenceCount(),
            request.reminderMinutesBefore(),
            request.standupEnabled());
    return MeetingResponse.from(meeting);
  }

  @DeleteMapping("/{meetingId}")
  @PreAuthorize(MANAGE)
  public ResponseEntity<Void> delete(@PathVariable UUID meetingId) {
    meetingService.delete(meetingId);
    return ResponseEntity.noContent().build();
  }

  private static Set<DayOfWeek> weekdaySet(List<String> codes) {
    return codes == null || codes.isEmpty()
        ? Set.of()
        : MeetingOccurrenceCalculator.parseWeekdays(String.join(",", codes));
  }
}
