package com.app.tracker.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.automation.AutomationRuleService.TemplateStatus;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.dependency.service.TaskDependencyService;
import com.app.tracker.integration.service.GithubEventProcessor;
import com.app.tracker.notification.service.NotificationService;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Urunlestirme Dalga 3.1 (ADR-0016) — engine/gate/dongu-korumasi TaskActivityIntegrationTest ile
 * AYNI desende (Kafka consumer'i atlayip is mantigini dogrudan cagirarak) test edilir; consumer'in
 * kendisi yalniz ince bir ayristirma/yonlendirme katmani oldugu icin ayrica test edilmez.
 */
@SpringBootTest
class AutomationRuleIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TaskDependencyService taskDependencyService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private AutomationRuleService automationRuleService;
  @Autowired private AutomationRuleEngine automationRuleEngine;
  @Autowired private AutomationOverdueAlertService automationOverdueAlertService;
  @Autowired private GithubEventProcessor githubEventProcessor;
  @Autowired private NotificationService notificationService;

  private UUID workspaceId;
  private Project project;
  private UUID adminUserId;
  private UUID developerUserId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(
        null, () -> workspaceService.createWorkspace(workspaceId, "Automation WS"));
    project = inWorkspace(() -> projectService.createProject("AUT", "Automation Project"));
    String adminEmail = "aut-admin-" + UUID.randomUUID() + "@tracker.local";
    adminUserId = authService.register(adminEmail, PASSWORD, "Admin").getId();
    membershipService.addMember(workspaceId, adminUserId, WorkspaceRole.ADMIN);
    String devEmail = "aut-dev-" + UUID.randomUUID() + "@tracker.local";
    developerUserId = authService.register(devEmail, PASSWORD, "Gelistirici").getId();
    membershipService.addMember(workspaceId, developerUserId, WorkspaceRole.DEVELOPER);
  }

  @Test
  void defaultStatusesMatchAdrOnlyPrMergeIsOptOut() {
    List<TemplateStatus> statuses =
        inWorkspace(() -> automationRuleService.statusesForProject(project.getId()));
    assertEquals(AutomationTemplateKey.values().length, statuses.size());
    for (TemplateStatus status : statuses) {
      assertFalse(status.configured());
      assertEquals(
          status.templateKey() == AutomationTemplateKey.PR_MERGE_TO_DONE, status.enabled());
    }
  }

  @Test
  void setEnabledPersistsAndIsReflectedInStatuses() {
    inWorkspace(
        () ->
            automationRuleService.setEnabled(
                project.getId(),
                AutomationTemplateKey.SUBTASK_ALL_DONE_PARENT_TO_REVIEW,
                true,
                adminUserId));

    List<TemplateStatus> statuses =
        inWorkspace(() -> automationRuleService.statusesForProject(project.getId()));
    TemplateStatus subtaskRule =
        statuses.stream()
            .filter(s -> s.templateKey() == AutomationTemplateKey.SUBTASK_ALL_DONE_PARENT_TO_REVIEW)
            .findFirst()
            .orElseThrow();
    assertTrue(subtaskRule.enabled());
    assertTrue(subtaskRule.configured());
  }

  @Test
  void allSubtasksDoneMovesParentToReviewOnlyWhenEnabled() {
    Task parent = inWorkspace(() -> taskService.createTask(project.getId(), "Parent"));
    Task child1 = inWorkspace(() -> taskService.createTask(project.getId(), "Child 1"));
    Task child2 = inWorkspace(() -> taskService.createTask(project.getId(), "Child 2"));
    inWorkspace(() -> taskService.setParent(child1.getId(), parent.getId(), adminUserId));
    inWorkspace(() -> taskService.setParent(child2.getId(), parent.getId(), adminUserId));
    inWorkspace(() -> taskService.updateStatus(child1.getId(), TaskStatus.DONE, adminUserId));

    // Kural kapaliyken: ikinci alt gorev de Done olsa parent ilerlemez.
    inWorkspace(() -> taskService.updateStatus(child2.getId(), TaskStatus.DONE, adminUserId));
    UUID eventIdDisabled = UUID.randomUUID();
    inWorkspace(() -> automationRuleEngine.onTaskDone(eventIdDisabled, child2.getId()));
    Task parentAfterDisabled = inWorkspace(() -> taskService.getDetail(parent.getId()).task());
    assertEquals(TaskStatus.TO_DO, parentAfterDisabled.getStatus());

    // Kurali ac, geri al ve tekrar Done yap (farkli eventId — ProcessedEventStore ayni eventId'yi
    // tekrar islemez).
    inWorkspace(
        () ->
            automationRuleService.setEnabled(
                project.getId(),
                AutomationTemplateKey.SUBTASK_ALL_DONE_PARENT_TO_REVIEW,
                true,
                adminUserId));
    inWorkspace(
        () -> taskService.updateStatus(child2.getId(), TaskStatus.IN_PROGRESS, adminUserId));
    inWorkspace(() -> taskService.updateStatus(child2.getId(), TaskStatus.DONE, adminUserId));
    UUID eventIdEnabled = UUID.randomUUID();
    inWorkspace(() -> automationRuleEngine.onTaskDone(eventIdEnabled, child2.getId()));

    Task parentAfterEnabled = inWorkspace(() -> taskService.getDetail(parent.getId()).task());
    assertEquals(TaskStatus.REVIEW, parentAfterEnabled.getStatus());
  }

  @Test
  void blockerDoneNotifiesAssigneeAndWatcherOfBlockedTaskWhenEnabled() {
    Task blocked = inWorkspace(() -> taskService.createTask(project.getId(), "Blocked"));
    Task blocking = inWorkspace(() -> taskService.createTask(project.getId(), "Blocking"));
    inWorkspace(() -> taskDependencyService.link(blocked.getId(), blocking.getId(), adminUserId));
    inWorkspace(() -> taskService.assign(blocked.getId(), developerUserId, adminUserId));
    inWorkspace(
        () ->
            automationRuleService.setEnabled(
                project.getId(), AutomationTemplateKey.BLOCKER_DONE_NOTIFY, true, adminUserId));

    inWorkspace(() -> taskService.updateStatus(blocking.getId(), TaskStatus.DONE, adminUserId));
    UUID eventId = UUID.randomUUID();
    inWorkspace(() -> automationRuleEngine.onTaskDone(eventId, blocking.getId()));

    int notifications =
        inWorkspace(
            () -> notificationService.listMine(developerUserId, 10, null, false).data().size());
    assertEquals(1, notifications);
  }

  @Test
  void assignedTodoTaskMovesToInProgressOnlyWhenEnabled() {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T"));

    inWorkspace(() -> taskService.assign(task.getId(), developerUserId, adminUserId));
    inWorkspace(() -> automationRuleEngine.onTaskAssigned(UUID.randomUUID(), task.getId()));
    Task afterDisabled = inWorkspace(() -> taskService.getDetail(task.getId()).task());
    assertEquals(TaskStatus.TO_DO, afterDisabled.getStatus());

    inWorkspace(
        () ->
            automationRuleService.setEnabled(
                project.getId(),
                AutomationTemplateKey.ASSIGNED_TODO_TO_IN_PROGRESS,
                true,
                adminUserId));
    inWorkspace(() -> taskService.assign(task.getId(), null, adminUserId));
    inWorkspace(() -> taskService.assign(task.getId(), developerUserId, adminUserId));
    inWorkspace(() -> automationRuleEngine.onTaskAssigned(UUID.randomUUID(), task.getId()));

    Task afterEnabled = inWorkspace(() -> taskService.getDetail(task.getId()).task());
    assertEquals(TaskStatus.IN_PROGRESS, afterEnabled.getStatus());
  }

  @Test
  void prMergeToDoneCanBeDisabled() {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "Fix login"));
    inWorkspace(
        () ->
            automationRuleService.setEnabled(
                project.getId(), AutomationTemplateKey.PR_MERGE_TO_DONE, false, adminUserId));

    String body = prBody("closed", true, "AUT-" + task.getTaskNumber() + " fix login");
    int advanced =
        inWorkspace(() -> githubEventProcessor.process(UUID.randomUUID(), "pull_request", body));

    assertEquals(0, advanced);
    Task afterDisabled = inWorkspace(() -> taskService.getDetail(task.getId()).task());
    assertEquals(TaskStatus.TO_DO, afterDisabled.getStatus());
  }

  @Test
  void overdueCheckIsNoOpWhenDisabledOrNoOverdueTasksExist() {
    // rejectPastDueDate gecmis tarihe izin vermedigi icin gercek bir "gecmis" gorev bu testte
    // kurulamaz (TaskService.updateDueDate reddeder); OVERDUE_NOTIFY'in kapaliyken VE hicbir gorev
    // gecmemisken no-op oldugu burada dogrulanir. Gunluk dedup mekanizmasi
    // (AutomationNotificationLedgerRepository, task_aging_alerts ile AYNI ON CONFLICT DO NOTHING
    // deseni) ayri bir servis girisi olmadigi icin burada tekrar test edilmiyor.
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T"));
    inWorkspace(
        () -> taskService.updateDueDate(task.getId(), LocalDate.now().plusDays(7), adminUserId));

    var disabled = inWorkspace(() -> automationOverdueAlertService.checkProject(project));
    assertTrue(disabled.isEmpty());

    inWorkspace(
        () ->
            automationRuleService.setEnabled(
                project.getId(), AutomationTemplateKey.OVERDUE_NOTIFY, true, adminUserId));
    var enabledButNotOverdue =
        inWorkspace(() -> automationOverdueAlertService.checkProject(project));
    assertTrue(enabledButNotOverdue.isEmpty());
  }

  private static String prBody(String action, boolean merged, String title) {
    return "{\"action\":\""
        + action
        + "\",\"pull_request\":{\"merged\":"
        + merged
        + ",\"draft\":false,\"title\":\""
        + title
        + "\",\"body\":\"\",\"head\":{\"ref\":\"feature\"}}}";
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  private void inWorkspace(Runnable action) {
    tenantExecutor.runAs(workspaceId, action);
  }
}
