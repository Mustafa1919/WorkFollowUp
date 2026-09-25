package com.app.tracker.standup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.analytics.model.TaskAnalytics;
import com.app.tracker.analytics.repository.TaskAnalyticsRepository;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.dependency.service.TaskDependencyService;
import com.app.tracker.flow.repository.TaskAgingAlertRepository;
import com.app.tracker.integration.IntegrationActor;
import com.app.tracker.meeting.model.Meeting;
import com.app.tracker.meeting.model.MeetingFrequency;
import com.app.tracker.meeting.service.MeetingService;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.standup.dto.StandupDigestResponse;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dalga 2.3 — standup ozeti uretimi (6 "olgu" kategorisi), idempotency ve not sahiplik kontrolu.
 * Gunluk olgular icin gercek servis cagrilari (assign/updateStatus/link) yeterliyken, ZAMANA BAGLI
 * olanlar ("dun") icin task_events/task_analytics'e DOGRUDAN native SQL ile yazilir — uretim
 * kodunun {@code created_at}/{@code done_at} icin DB {@code NOW()} kullanmasi API'den gecmis tarih
 * yazmayi engelliyor (AnalyticsQueryServiceIntegrationTest/ForecastServiceIntegrationTest ile AYNI
 * yaklasim).
 */
@SpringBootTest
class StandupDigestServiceIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TaskDependencyService taskDependencyService;
  @Autowired private MeetingService meetingService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private StandupDigestService digestService;
  @Autowired private TaskAnalyticsRepository taskAnalyticsRepository;
  @Autowired private TaskAgingAlertRepository taskAgingAlertRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private Clock clock;

  private UUID workspaceId;
  private Project project;
  private UUID adminId;
  private UUID devId;
  private LocalDate yesterday;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Standup WS"));
    project = inWorkspace(() -> projectService.createProject("SUP", "Standup Project"));
    adminId = registerMember("standup-admin", WorkspaceRole.ADMIN);
    devId = registerMember("standup-dev", WorkspaceRole.DEVELOPER);
    yesterday = StandupDigestService.previousBusinessDay(LocalDate.now(clock));
  }

  @Test
  void generatesFactsForEachCategoryAndIsIdempotent() {
    Task completed = assignedTask("Tamamlanan");
    seedDoneYesterday(completed.getId());

    Task inProgress = assignedTask("Devam eden");
    inWorkspace(() -> taskService.updateStatus(inProgress.getId(), TaskStatus.IN_PROGRESS, devId));

    Task progressedYesterday = assignedTask("Dun ilerleyen");
    seedStatusEventYesterday(progressedYesterday.getId(), devId, "Review");

    Task blocked = assignedTask("Bloklanan");
    Task blocker = inWorkspace(() -> taskService.createTask(project.getId(), "Blocker"));
    inWorkspace(() -> taskDependencyService.link(blocked.getId(), blocker.getId(), adminId));

    Task aging = assignedTask("Takilan");
    inTenant(() -> taskAgingAlertRepository.upsertLevel(aging.getId(), workspaceId, 1));

    Task githubTask = assignedTask("GitHub'dan gelen");
    seedStatusEventYesterday(githubTask.getId(), IntegrationActor.SYSTEM_USER_ID, "Done");

    Meeting meeting = createStandupMeeting();
    LocalDate today = LocalDate.now(clock);

    List<Notification> created = inWorkspace(() -> digestService.generate(meeting, today));
    assertEquals(2, created.size()); // admin + dev, ikisi de recipient

    List<StandupDigestResponse> digests =
        inWorkspace(() -> digestService.list(meeting.getId(), today));
    StandupDigestResponse devDigest =
        digests.stream().filter(d -> d.userId().equals(devId)).findFirst().orElseThrow();

    assertTaskIds(devDigest.facts().completedYesterday(), completed.getId());
    assertTaskIds(devDigest.facts().inProgress(), inProgress.getId());
    assertTaskIds(devDigest.facts().progressedYesterday(), progressedYesterday.getId());
    assertTaskIds(devDigest.facts().blocked(), blocked.getId());
    assertTaskIds(devDigest.facts().aging(), aging.getId());
    assertTaskIds(devDigest.facts().githubActivity(), githubTask.getId());

    // Ayni gun icin tekrar calisma: yeni bildirim YOK, mevcut satirlar korunur.
    List<Notification> secondRun = inWorkspace(() -> digestService.generate(meeting, today));
    assertTrue(secondRun.isEmpty());
    assertEquals(2, inWorkspace(() -> digestService.list(meeting.getId(), today)).size());
  }

  @Test
  void noteCanOnlyBeUpdatedByItsOwner() {
    Meeting meeting = createStandupMeeting();
    LocalDate today = LocalDate.now(clock);
    inWorkspace(() -> digestService.generate(meeting, today));

    inWorkspace(
        () -> {
          digestService.updateNote(meeting.getId(), today, devId, "Bugun bunu bitirecegim");
          return null;
        });
    StandupDigestResponse devDigest =
        inWorkspace(() -> digestService.list(meeting.getId(), today)).stream()
            .filter(d -> d.userId().equals(devId))
            .findFirst()
            .orElseThrow();
    assertEquals("Bugun bunu bitirecegim", devDigest.note());

    // Digest satiri olmayan bir kullanici (rastgele UUID) icin guncelleme 404.
    assertThrows(
        ResourceNotFoundException.class,
        () ->
            inWorkspace(
                () -> {
                  digestService.updateNote(meeting.getId(), today, UUID.randomUUID(), "x");
                  return null;
                }));
  }

  // ---- yardimcilar --------------------------------------------------------------------------

  private Meeting createStandupMeeting() {
    LocalDate start = LocalDate.now(clock).plusDays(1);
    return inWorkspace(
        () ->
            meetingService.create(
                "Daily",
                null,
                null,
                start,
                LocalTime.of(9, 30),
                15,
                MeetingFrequency.DAILY,
                1,
                Set.of(),
                null,
                null,
                null,
                true,
                adminId));
  }

  private Task assignedTask(String title) {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), title));
    return inWorkspace(() -> taskService.assign(task.getId(), devId, adminId));
  }

  private void seedDoneYesterday(UUID taskId) {
    var atNoon = yesterday.atTime(12, 0).atZone(clock.getZone()).toInstant();
    TaskAnalytics analytics = TaskAnalytics.create(taskId, workspaceId, project.getId());
    analytics.applyStatusChange(TaskStatus.DONE, atNoon);
    inTenant(() -> taskAnalyticsRepository.save(analytics));
  }

  private void seedStatusEventYesterday(UUID taskId, UUID actorId, String newStatus) {
    var atNoon = yesterday.atTime(12, 0).atZone(clock.getZone()).toInstant();
    inTenant(
        () ->
            entityManager
                .createNativeQuery(
                    "INSERT INTO task_events (id, task_id, actor_id, event_type, old_value, "
                        + "new_value, created_at) VALUES (?1, ?2, ?3, 'status_changed', "
                        + "CAST('{}' AS jsonb), CAST(?4 AS jsonb), ?5)")
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, taskId)
                .setParameter(3, actorId)
                .setParameter(4, "{\"status\":\"" + newStatus + "\"}")
                .setParameter(5, atNoon)
                .executeUpdate());
  }

  private static void assertTaskIds(List<StandupTaskRef> refs, UUID expectedTaskId) {
    assertEquals(1, refs.size());
    assertEquals(expectedTaskId, refs.get(0).taskId());
  }

  private UUID registerMember(String prefix, String role) {
    String email = prefix + "-" + UUID.randomUUID() + "@tracker.local";
    UUID userId = authService.register(email, PASSWORD, "User").getId();
    membershipService.addMember(workspaceId, userId, role);
    return userId;
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  private <T> T inTenant(Supplier<T> action) {
    return transactionTemplate.execute(
        status -> {
          entityManager
              .createNativeQuery("SELECT set_config('app.current_workspace_id', ?1, true)")
              .setParameter(1, workspaceId.toString())
              .getSingleResult();
          return action.get();
        });
  }

  private void inTenant(Runnable action) {
    inTenant(
        () -> {
          action.run();
          return null;
        });
  }
}
