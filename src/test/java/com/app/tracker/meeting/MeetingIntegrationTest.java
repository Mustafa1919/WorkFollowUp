package com.app.tracker.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.meeting.model.Meeting;
import com.app.tracker.meeting.model.MeetingFrequency;
import com.app.tracker.meeting.service.MeetingService;
import com.app.tracker.meeting.service.MeetingService.Occurrence;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Toplanti planlama (RAKIP_ANALIZI.md sonrasi Hedefler.md karari) — CRUD, is kurallari (gecmis
 * tarih yasagi, WEEKLY icin gun zorunlulugu, until/count karsilikli disliligi), occurrence
 * duzlestirme ve RLS izolasyonu. TaskSubtaskAndDependencyIntegrationTest ile AYNI desen (servis
 * katmani + tenantExecutor.runAs, HTTP rol matrisi ayrica HttpAuthorizationIntegrationTest'te).
 */
@SpringBootTest
class MeetingIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private MeetingService meetingService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;

  private UUID workspaceId;
  private UUID adminUserId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Meeting WS"));
    String email = "meeting-owner-" + UUID.randomUUID() + "@tracker.local";
    adminUserId = authService.register(email, PASSWORD, "Owner").getId();
    membershipService.addMember(workspaceId, adminUserId, WorkspaceRole.ADMIN);
  }

  @Test
  void createUpdateAndDeleteMeeting() {
    LocalDate start = LocalDate.now().plusDays(7);
    Meeting created =
        inWorkspace(
            () ->
                meetingService.create(
                    "Standup",
                    "Gunluk senkron",
                    "https://meet.example/abc",
                    start,
                    LocalTime.of(9, 30),
                    15,
                    MeetingFrequency.WEEKLY,
                    1,
                    Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                    null,
                    null,
                    15,
                    adminUserId));
    assertEquals("Standup", created.getTitle());
    assertEquals(1, inWorkspace(() -> meetingService.list()).size());

    Meeting updated =
        inWorkspace(
            () ->
                meetingService.update(
                    created.getId(),
                    "Standup (guncel)",
                    null,
                    null,
                    start,
                    LocalTime.of(10, 0),
                    15,
                    MeetingFrequency.WEEKLY,
                    1,
                    Set.of(DayOfWeek.MONDAY),
                    null,
                    null,
                    null));
    assertEquals("Standup (guncel)", updated.getTitle());
    assertEquals(LocalTime.of(10, 0), updated.getStartTime());

    inWorkspace(
        () -> {
          meetingService.delete(created.getId());
          return null;
        });
    assertTrue(inWorkspace(() -> meetingService.list()).isEmpty());
  }

  @Test
  void pastStartDateIsRejected() {
    LocalDate yesterday = LocalDate.now().minusDays(1);
    assertThrows(
        BusinessRuleException.class,
        () ->
            inWorkspace(
                () ->
                    meetingService.create(
                        "T",
                        null,
                        null,
                        yesterday,
                        LocalTime.NOON,
                        30,
                        MeetingFrequency.ONCE,
                        1,
                        Set.of(),
                        null,
                        null,
                        null,
                        adminUserId)));
  }

  @Test
  void weeklyWithoutWeekdaysIsRejected() {
    LocalDate start = LocalDate.now().plusDays(1);
    assertThrows(
        BusinessRuleException.class,
        () ->
            inWorkspace(
                () ->
                    meetingService.create(
                        "T",
                        null,
                        null,
                        start,
                        LocalTime.NOON,
                        30,
                        MeetingFrequency.WEEKLY,
                        1,
                        Set.of(),
                        null,
                        null,
                        null,
                        adminUserId)));
  }

  @Test
  void untilAndCountTogetherIsRejected() {
    LocalDate start = LocalDate.now().plusDays(1);
    assertThrows(
        BusinessRuleException.class,
        () ->
            inWorkspace(
                () ->
                    meetingService.create(
                        "T",
                        null,
                        null,
                        start,
                        LocalTime.NOON,
                        30,
                        MeetingFrequency.DAILY,
                        1,
                        Set.of(),
                        start.plusDays(10),
                        5,
                        null,
                        adminUserId)));
  }

  @Test
  void unknownMeetingIsNotFound() {
    assertThrows(
        ResourceNotFoundException.class,
        () ->
            inWorkspace(
                () -> {
                  meetingService.delete(UUID.randomUUID());
                  return null;
                }));
  }

  @Test
  void occurrencesInRangeFlattensAcrossMeetings() {
    LocalDate start = LocalDate.now().plusDays(1);
    inWorkspace(
        () ->
            meetingService.create(
                "Daily A",
                null,
                null,
                start,
                LocalTime.of(9, 0),
                15,
                MeetingFrequency.DAILY,
                1,
                Set.of(),
                null,
                null,
                null,
                adminUserId));
    inWorkspace(
        () ->
            meetingService.create(
                "Once B",
                null,
                null,
                start.plusDays(2),
                LocalTime.of(14, 0),
                60,
                MeetingFrequency.ONCE,
                1,
                Set.of(),
                null,
                null,
                null,
                adminUserId));

    List<Occurrence> occurrences =
        inWorkspace(() -> meetingService.occurrencesInRange(start, start.plusDays(2)));
    assertEquals(4, occurrences.size()); // Daily A x3 gun + Once B x1
    // Ayni gunde iki toplanti varsa saat sirasina gore siralanmali.
    Occurrence lastDay0 = occurrences.get(2);
    assertEquals(start.plusDays(2), lastDay0.date());
  }

  @Test
  void meetingsAreIsolatedBetweenWorkspaces() {
    UUID otherWorkspaceId = UUID.randomUUID();
    tenantExecutor.runAs(
        null, () -> workspaceService.createWorkspace(otherWorkspaceId, "Other WS"));
    String email = "meeting-other-" + UUID.randomUUID() + "@tracker.local";
    UUID otherUserId = authService.register(email, PASSWORD, "Other").getId();
    membershipService.addMember(otherWorkspaceId, otherUserId, WorkspaceRole.ADMIN);

    inWorkspace(
        () ->
            meetingService.create(
                "WS1 meeting",
                null,
                null,
                LocalDate.now().plusDays(1),
                LocalTime.NOON,
                30,
                MeetingFrequency.ONCE,
                1,
                Set.of(),
                null,
                null,
                null,
                adminUserId));

    List<Meeting> visibleFromOther =
        tenantExecutor.runAs(otherWorkspaceId, () -> meetingService.list());
    assertTrue(visibleFromOther.isEmpty());
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }
}
