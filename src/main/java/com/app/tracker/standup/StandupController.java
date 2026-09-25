package com.app.tracker.standup;

import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.standup.dto.StandupDigestResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dalga 2.3 — standup ozetleri okuma + kendi notunu guncelleme. Rol siniri yok: takim ozetini
 * herkes okuyabilir ({@code MeetingReminderService}'in alici kuralindan BAGIMSIZ — okuma her zaman
 * ALL_ROLES, tipki analitik/aging/forecast okumalari gibi).
 */
@RestController
@RequestMapping("/api/v1/standups")
public class StandupController {

  private final StandupDigestService digestService;

  public StandupController(StandupDigestService digestService) {
    this.digestService = digestService;
  }

  @GetMapping
  public List<StandupDigestResponse> list(
      @RequestParam UUID meetingId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return digestService.list(meetingId, date);
  }

  @PutMapping("/{meetingId}/{date}/note")
  public void updateNote(
      @PathVariable UUID meetingId,
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @Valid @RequestBody NoteRequest request) {
    digestService.updateNote(meetingId, date, CurrentUser.id(), request.note());
  }

  public record NoteRequest(@Size(max = 2000) String note) {}
}
