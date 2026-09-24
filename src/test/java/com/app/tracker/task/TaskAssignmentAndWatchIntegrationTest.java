package com.app.tracker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.notification.consumer.InboxNotificationConsumer;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.service.NotificationMessageFormatter;
import com.app.tracker.notification.service.NotificationService;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.task.service.TaskService.TaskDetail;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMemberService;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Urunlestirme Dalga 1.1 (V22): atanan kisi, aciklama, izleyiciler ve Inbox'in "izleyiciler −
 * aktor" alici modeli. Servis katmani + tenantExecutor.runAs (TaskSubtaskAndDependency deseni);
 * Inbox, NotificationIntegrationTest gibi consumer'a elle zarf verilerek sinanir.
 */
@SpringBootTest
class TaskAssignmentAndWatchIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private WorkspaceMemberService memberService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private InboxNotificationConsumer consumer;
  @Autowired private NotificationService notificationService;
  @Autowired private ObjectMapper objectMapper;

  private UUID workspaceId;
  private Project project;
  private UUID adminId;
  private UUID developerId;
  private UUID viewerId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Assign WS"));
    project = inWorkspace(() -> projectService.createProject("ASG", "Atama Projesi"));
    adminId = newMember(WorkspaceRole.ADMIN);
    developerId = newMember(WorkspaceRole.DEVELOPER);
    viewerId = newMember(WorkspaceRole.VIEWER);
  }

  // ---- atama

  @Test
  void creatorAndAssigneeBecomeWatchersAndAssignmentIsIdempotent() {
    Task task = create();
    assertEquals(adminId, task.getCreatedBy());

    Task assigned = inWorkspace(() -> taskService.assign(task.getId(), developerId, adminId));
    assertEquals(developerId, assigned.getAssigneeId());
    // Ayni kisiye tekrar atama no-op (hata degil).
    inWorkspace(() -> taskService.assign(task.getId(), developerId, adminId));

    TaskDetail detail = inWorkspace(() -> taskService.getDetail(task.getId()));
    assertEquals(List.of(adminId, developerId), detail.watcherIds());

    Task unassigned = inWorkspace(() -> taskService.assign(task.getId(), null, adminId));
    assertNull(unassigned.getAssigneeId());
    // Atama kaldirilinca izleme SURER: kullanici isterse kendisi birakir.
    assertTrue(
        inWorkspace(() -> taskService.getDetail(task.getId())).watcherIds().contains(developerId));
  }

  @Test
  void viewerOrNonMemberCannotBeAssigned() {
    Task task = create();
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskService.assign(task.getId(), viewerId, adminId)));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskService.assign(task.getId(), UUID.randomUUID(), adminId)));
  }

  @Test
  void approvedTaskLocksAssigneeAndDescription() {
    Task task = create();
    inWorkspace(() -> taskService.updateStatus(task.getId(), TaskStatus.DONE, adminId));
    inWorkspace(() -> taskService.approve(task.getId(), adminId));

    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskService.assign(task.getId(), developerId, adminId)));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskService.updateDescription(task.getId(), "yeni", adminId)));
  }

  @Test
  void myTasksListsOnlyOpenTasksAssignedToMeAcrossProjects() {
    Project other = inWorkspace(() -> projectService.createProject("OTH", "Diger"));
    Task first = create();
    Task second =
        inWorkspace(() -> taskService.createTask(other.getId(), "Diger proje", null, adminId));
    Task notMine = create();
    inWorkspace(() -> taskService.assign(first.getId(), developerId, adminId));
    inWorkspace(() -> taskService.assign(second.getId(), developerId, adminId));
    inWorkspace(() -> taskService.assign(notMine.getId(), adminId, adminId));

    List<UUID> mine =
        inWorkspace(() -> taskService.listAssignedTo(developerId, 1, null)).data().stream()
            .map(Task::getId)
            .toList();
    assertEquals(1, mine.size(), "limit uygulanir");

    var firstPage = inWorkspace(() -> taskService.listAssignedTo(developerId, 1, null));
    var secondPage =
        inWorkspace(() -> taskService.listAssignedTo(developerId, 1, firstPage.nextCursor()));
    assertTrue(firstPage.hasMore());
    assertFalse(secondPage.hasMore());
    assertEquals(
        java.util.Set.of(first.getId(), second.getId()),
        java.util.Set.of(firstPage.data().get(0).getId(), secondPage.data().get(0).getId()));
  }

  // ---- aciklama

  @Test
  void descriptionIsStoredAndBlankClearsIt() {
    Task task = create();
    inWorkspace(() -> taskService.updateDescription(task.getId(), "## Kabul kriteri", adminId));
    assertEquals(
        "## Kabul kriteri",
        inWorkspace(() -> taskService.getDetail(task.getId())).task().getDescription());

    inWorkspace(() -> taskService.updateDescription(task.getId(), "   ", adminId));
    assertNull(inWorkspace(() -> taskService.getDetail(task.getId())).task().getDescription());
  }

  // ---- izleme

  @Test
  void watchAndUnwatchAreIdempotent() {
    Task task = create();
    inWorkspace(() -> taskService.watch(task.getId(), viewerId));
    inWorkspace(() -> taskService.watch(task.getId(), viewerId));
    assertTrue(
        inWorkspace(() -> taskService.getDetail(task.getId())).watcherIds().contains(viewerId));

    inWorkspace(() -> taskService.unwatch(task.getId(), viewerId));
    inWorkspace(() -> taskService.unwatch(task.getId(), viewerId));
    assertFalse(
        inWorkspace(() -> taskService.getDetail(task.getId())).watcherIds().contains(viewerId));
  }

  // ---- Inbox alicilari

  @Test
  void assignmentNotifiesNewAssigneeWithPersonalBodyAndOtherWatchersGenerically() {
    Task task = create(); // admin olusturan -> izleyici
    inWorkspace(() -> taskService.watch(task.getId(), viewerId)); // VIEWER kendisi izlemeyi secti

    // Zarf elle verilir: developer izleyici listesinde YOK (TaskService.assign cagrilmadi).
    consumer.onMessage(assignedEnvelope(task, developerId, adminId));

    List<Notification> developerInbox = forUser(developerId);
    assertEquals(1, developerInbox.size(), "atanan izlemese bile bildirim alir");
    assertEquals(NotificationMessageFormatter.ASSIGNED_TO_YOU, developerInbox.get(0).getBody());
    assertTrue(forUser(adminId).isEmpty(), "aktor (izleyici olsa da) haric");
    List<Notification> viewerInbox = forUser(viewerId);
    assertEquals(1, viewerInbox.size(), "izlemeyi secen VIEWER bildirim alir");
    assertEquals("Atanan kisi degisti.", viewerInbox.get(0).getBody());
  }

  @Test
  void selfAssignmentDoesNotNotifyTheActor() {
    Task task = create();
    consumer.onMessage(assignedEnvelope(task, adminId, adminId));
    assertTrue(forUser(adminId).isEmpty());
  }

  @Test
  void removedMemberStopsReceivingNotificationsForWatchedTasks() {
    Task task = create();
    inWorkspace(() -> taskService.watch(task.getId(), developerId));
    tenantExecutor.runAs(workspaceId, () -> memberService.removeMember(developerId));

    consumer.onMessage(statusEnvelope(task, adminId));

    assertTrue(forUser(developerId).isEmpty());
  }

  // ---- yardimcilar

  private Task create() {
    return inWorkspace(() -> taskService.createTask(project.getId(), "Gorev", null, adminId));
  }

  private UUID newMember(String role) {
    String email = "asg-" + UUID.randomUUID() + "@tracker.local";
    UUID userId = authService.register(email, PASSWORD, "Uye " + role).getId();
    membershipService.addMember(workspaceId, userId, role);
    return userId;
  }

  private List<Notification> forUser(UUID userId) {
    return inWorkspace(() -> notificationService.listMine(userId, 50, null, false).data());
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  private String assignedEnvelope(Task task, UUID newAssigneeId, UUID actorId) {
    ObjectNode payload = basePayload(task, actorId);
    payload.putNull("oldAssigneeId");
    payload.put("newAssigneeId", newAssigneeId.toString());
    return envelope("TASK_ASSIGNED", payload);
  }

  private String statusEnvelope(Task task, UUID actorId) {
    ObjectNode payload = basePayload(task, actorId);
    payload.put("oldStatus", "To Do");
    payload.put("newStatus", "In Progress");
    return envelope("TASK_STATUS_UPDATED", payload);
  }

  private ObjectNode basePayload(Task task, UUID actorId) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", project.getId().toString());
    payload.put("actorId", actorId.toString());
    return payload;
  }

  private String envelope(String eventType, ObjectNode payload) {
    ObjectNode envelope = objectMapper.createObjectNode();
    envelope.put("eventId", UUID.randomUUID().toString());
    envelope.put("eventType", eventType);
    envelope.put("schemaVersion", 1);
    envelope.put("workspaceId", workspaceId.toString());
    envelope.set("payload", payload);
    return objectMapper.writeValueAsString(envelope);
  }
}
