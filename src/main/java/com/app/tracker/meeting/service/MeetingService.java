package com.app.tracker.meeting.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.meeting.model.Meeting;
import com.app.tracker.meeting.model.MeetingFrequency;
import com.app.tracker.meeting.repository.MeetingRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Toplantı serisi CRUD + occurrence düzleştirme. Occurrence'lar DB'de tutulmadığı için (bkz. {@code
 * Meeting} javadoc'u) tek bir tekrarı düzenleme/iptal API'si YOK — düzenleme/silme her zaman SERİ
 * genelini etkiler (v1 kapsam kararı, bkz. Hedefler.md).
 */
@Service
public class MeetingService {

  private final MeetingRepository meetingRepository;
  private final Clock clock;

  public MeetingService(MeetingRepository meetingRepository, Clock clock) {
    this.meetingRepository = meetingRepository;
    this.clock = clock;
  }

  @Transactional
  public Meeting create(
      String title,
      String description,
      String meetingUrl,
      LocalDate startDate,
      LocalTime startTime,
      int durationMinutes,
      String frequency,
      int intervalCount,
      Set<DayOfWeek> byWeekday,
      LocalDate untilDate,
      Integer occurrenceCount,
      Integer reminderMinutesBefore,
      UUID actorId) {
    UUID workspaceId = requireWorkspace();
    validate(startDate, frequency, byWeekday, untilDate, occurrenceCount);
    Meeting meeting =
        Meeting.of(
            UUID.randomUUID(),
            workspaceId,
            title.trim(),
            normalize(description),
            normalize(meetingUrl),
            startDate,
            startTime,
            durationMinutes,
            frequency,
            intervalCount,
            csvOrNull(frequency, byWeekday),
            untilDate,
            occurrenceCount,
            reminderMinutesBefore,
            actorId);
    return meetingRepository.save(meeting);
  }

  @Transactional
  public Meeting update(
      UUID meetingId,
      String title,
      String description,
      String meetingUrl,
      LocalDate startDate,
      LocalTime startTime,
      int durationMinutes,
      String frequency,
      int intervalCount,
      Set<DayOfWeek> byWeekday,
      LocalDate untilDate,
      Integer occurrenceCount,
      Integer reminderMinutesBefore) {
    Meeting meeting = requireMeeting(meetingId);
    validate(startDate, frequency, byWeekday, untilDate, occurrenceCount);
    meeting.apply(
        title.trim(),
        normalize(description),
        normalize(meetingUrl),
        startDate,
        startTime,
        durationMinutes,
        frequency,
        intervalCount,
        csvOrNull(frequency, byWeekday),
        untilDate,
        occurrenceCount,
        reminderMinutesBefore);
    return meetingRepository.save(meeting);
  }

  @Transactional
  public void delete(UUID meetingId) {
    meetingRepository.delete(requireMeeting(meetingId));
  }

  @Transactional(readOnly = true)
  public List<Meeting> list() {
    return meetingRepository.findAllByOrderByStartDateAscStartTimeAsc();
  }

  /**
   * {@link MeetingReminderJob} için — repository'i DOĞRUDAN ÇAĞIRMAZ çünkü o bir
   * {@code @Transactional} sınırı taşımaz (bkz. {@code WorkspaceService#findAllWorkspaceIds}
   * javadoc'u: {@code TenancyGuardAspect} repository proxy'sinin kendi örtük transaction'ını
   * "aktif" saymaz, bu yüzden RLS GUC'u hiç ayarlanmaz ve her satır sessizce filtrelenir — hata
   * fırlatılmaz, sadece boş liste döner). Transaction burada, SERVİS katmanında açılmalı.
   */
  @Transactional(readOnly = true)
  public List<Meeting> listWithReminders() {
    return meetingRepository.findAllByReminderMinutesBeforeIsNotNull();
  }

  /**
   * Takvim görünümü: TÜM serilerin {@code [from, to]} aralığındaki occurrence'ları, düzleştirilmiş.
   */
  @Transactional(readOnly = true)
  public List<Occurrence> occurrencesInRange(LocalDate from, LocalDate to) {
    List<Occurrence> result = new ArrayList<>();
    for (Meeting meeting : list()) {
      for (LocalDate date : MeetingOccurrenceCalculator.occurrencesInRange(meeting, from, to)) {
        result.add(new Occurrence(meeting, date));
      }
    }
    result.sort(
        (a, b) -> {
          int byDate = a.date().compareTo(b.date());
          return byDate != 0
              ? byDate
              : a.meeting().getStartTime().compareTo(b.meeting().getStartTime());
        });
    return result;
  }

  public record Occurrence(Meeting meeting, LocalDate date) {}

  private void validate(
      LocalDate startDate,
      String frequency,
      Set<DayOfWeek> byWeekday,
      LocalDate untilDate,
      Integer occurrenceCount) {
    if (startDate.isBefore(LocalDate.now(clock))) {
      throw new BusinessRuleException("Toplantı başlangıç tarihi geçmişte olamaz.");
    }
    if (MeetingFrequency.WEEKLY.equals(frequency) && (byWeekday == null || byWeekday.isEmpty())) {
      throw new BusinessRuleException("Haftalık tekrar için en az bir gün seçilmelidir.");
    }
    if (untilDate != null && occurrenceCount != null) {
      throw new BusinessRuleException("Bitiş tarihi ve tekrar sayısı birlikte kullanılamaz.");
    }
    if (untilDate != null && untilDate.isBefore(startDate)) {
      throw new BusinessRuleException("Bitiş tarihi başlangıç tarihinden önce olamaz.");
    }
  }

  /** WEEKLY dışındaki frekanslarda {@code byWeekday} anlamsızdır — sessizce temizlenir. */
  private static String csvOrNull(String frequency, Set<DayOfWeek> byWeekday) {
    return MeetingFrequency.WEEKLY.equals(frequency)
        ? MeetingOccurrenceCalculator.toWeekdayCsv(byWeekday)
        : null;
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private Meeting requireMeeting(UUID meetingId) {
    return meetingRepository
        .findById(meetingId)
        .orElseThrow(() -> new ResourceNotFoundException("Toplantı bulunamadı."));
  }

  private static UUID requireWorkspace() {
    UUID workspaceId = TenantContext.getWorkspaceId();
    if (workspaceId == null) {
      throw new BusinessRuleException("Önce bir workspace seçmelisiniz (X-Workspace-Id header).");
    }
    return workspaceId;
  }
}
