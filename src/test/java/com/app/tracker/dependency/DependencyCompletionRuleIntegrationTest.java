package com.app.tracker.dependency;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.outbox.OutboxRelay;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.dependency.service.TaskDependencyService;
import com.app.tracker.integration.IntegrationActor;
import com.app.tracker.integration.service.GithubEventProcessor;
import com.app.tracker.notification.consumer.InboxNotificationConsumer;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.service.NotificationMessageFormatter;
import com.app.tracker.notification.service.NotificationMessageFormatter.TaskSummary;
import com.app.tracker.notification.service.NotificationService;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.dto.BulkOperation;
import com.app.tracker.task.dto.BulkTaskRequest;
import com.app.tracker.task.dto.UpdateTaskStatusRequest;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dilim 04 sozlesmesinden turetilen testler. HTTP mutasyonlari gercek JWT/rol filtresinden gecer;
 * GitHub -> outbox -> Inbox yolu elle ve senkron surulur. Sinif/test transaction'i yoktur: ret
 * sonrasi DB okumasi, istegin commit/rollback sonucunu gorur. R9 yalniz kaynak incelemesidir;
 * kaynak satirlari ve calistirilmamis sok-kir plani .dilim/dilim-04-bulgu.md dosyasindadir.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
class DependencyCompletionRuleIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final String BLOCKED_EVENT = "TASK_TRANSITION_BLOCKED";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthService authService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TaskDependencyService dependencyService;
  @Autowired private GithubEventProcessor githubEventProcessor;
  @Autowired private InboxNotificationConsumer inboxConsumer;
  @Autowired private NotificationService notificationService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;

  // Yalniz tasima zamanlayicisi durdurulur; sinanan servisler/repository/consumer gercektir.
  @MockitoBean private OutboxRelay outboxRelay;

  private UUID workspaceId;
  private Project project;
  private Member manager;

  private record Member(UUID id, String token) {}

  private record Counts(long audit, long outbox) {}

  private record OutboxRow(
      UUID id,
      String topic,
      String eventType,
      int schemaVersion,
      UUID aggregateId,
      UUID workspaceId,
      String payloadJson,
      Instant createdAt) {}

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(
        null, () -> workspaceService.createWorkspace(workspaceId, "Dependency WS"));
    manager = member(WorkspaceRole.MANAGER);
    project = inWorkspace(() -> projectService.createProject("DEP", "Completion rules"));
  }

  @Test
  void K1_doneBlockerAllowsDoneOverHttp() throws Exception {
    Task blocked = create(project, "Blocked");
    Task blocker = create(project, "Finished prerequisite");
    setStatus(blocker, TaskStatus.DONE);
    linkFixture(blocked, blocker);
    Counts before = counts();

    patchStatus(blocked, TaskStatus.DONE).andExpect(status().isOk());

    assertStatus(blocked, TaskStatus.DONE);
    assertEquals(new Counts(before.audit() + 1, before.outbox() + 1), counts());
  }

  @Test
  void K1_taskWithoutDependenciesAllowsDoneOverHttp() throws Exception {
    Task task = create(project, "Independent");

    patchStatus(task, TaskStatus.DONE).andExpect(status().isOk());

    assertStatus(task, TaskStatus.DONE);
  }

  @ParameterizedTest(name = "R1 open blocker in {0} rejects Done")
  @ValueSource(strings = {"To Do", "In Progress", "Review"})
  void R1_eachOpenBlockerStatusRejectsDoneWithoutStateOrEventWrites(String blockerStatus)
      throws Exception {
    Task blocked = create(project, "Blocked");
    Task blocker = create(project, "Open prerequisite");
    if (!TaskStatus.TO_DO.equals(blockerStatus)) {
      setStatus(blocker, blockerStatus);
    }
    linkFixture(blocked, blocker);
    JsonNode beforeTask = persistedTask(blocked);
    Counts before = counts();

    ResultActions result = patchStatus(blocked, TaskStatus.DONE).andExpect(status().isBadRequest());

    assertEquals(blockedDetail(blocked, List.of(key(project, blocker))), detail(result));
    assertEquals(beforeTask, persistedTask(blocked));
    assertEquals(before, counts(), "R1: task_events ve outbox satir farki sifir olmali");
  }

  @Test
  void R1_multipleBlockersUseOwnProjectKeysAndNumericTaskOrder() throws Exception {
    Task blocked = create(project, "Blocked");
    Project x = inWorkspace(() -> projectService.createProject("X", "X prerequisites"));
    Project y = inWorkspace(() -> projectService.createProject("Y", "Y prerequisites"));
    create(x, "Number one is unrelated");
    Task x2 = create(x, "Second prerequisite");
    for (int number = 3; number < 10; number++) {
      create(x, "Unrelated " + number);
    }
    Task x10 = create(x, "Tenth prerequisite");
    Task y1 = create(y, "Other project prerequisite");
    Task done = create(x, "Done prerequisite");
    Task deleted = create(y, "Deleted prerequisite");
    setStatus(done, TaskStatus.DONE);
    // Ters ekleme sirasi, sonuc siralamasini ekleme sirasindan ayirir.
    for (Task blocker : List.of(y1, x10, x2, done, deleted)) {
      linkFixture(blocked, blocker);
    }
    softDelete(deleted);
    assertEquals("X-2", key(x, x2));
    assertEquals("X-10", key(x, x10));
    JsonNode beforeTask = persistedTask(blocked);
    Counts before = counts();

    ResultActions result = patchStatus(blocked, TaskStatus.DONE).andExpect(status().isBadRequest());

    // Yoneticinin kabul ettigi sapma: metin sirasi yerine (proje anahtari, gorev no).
    assertEquals(blockedDetail(blocked, List.of("X-2", "X-10", "Y-1")), detail(result));
    assertEquals(beforeTask, persistedTask(blocked));
    assertEquals(before, counts());
  }

  @Test
  void K2_openBlockerAllowsInProgressAndReviewOverHttp() throws Exception {
    Task blocked = create(project, "Blocked");
    Task blocker = create(project, "Open prerequisite");
    linkFixture(blocked, blocker);
    assertStatus(blocked, TaskStatus.TO_DO);

    for (String target : List.of(TaskStatus.IN_PROGRESS, TaskStatus.REVIEW)) {
      patchStatus(blocked, target).andExpect(status().isOk());
      assertStatus(blocked, target);
      assertStatus(blocker, TaskStatus.TO_DO);
    }
  }

  @Test
  void K1_doneToDoneSkipsBlockersEvenAfterPrerequisiteReopens() throws Exception {
    Task blocked = create(project, "Already finished");
    Task blocker = create(project, "Reopened prerequisite");
    setStatus(blocker, TaskStatus.DONE);
    linkFixture(blocked, blocker);
    setStatus(blocked, TaskStatus.DONE);
    setStatus(blocker, TaskStatus.TO_DO);
    assertStatus(blocked, TaskStatus.DONE);

    patchStatus(blocked, TaskStatus.DONE).andExpect(status().isOk());

    assertStatus(blocked, TaskStatus.DONE);
    assertStatus(blocker, TaskStatus.TO_DO);
  }

  @Test
  void K3_softDeletedOpenBlockerAllowsDoneWithDependencyRowStillPresent() throws Exception {
    Task blocked = create(project, "Blocked");
    Task blocker = create(project, "Deleted prerequisite");
    linkFixture(blocked, blocker);
    softDelete(blocker);
    assertEquals(1L, dependencyCount(blocked, blocker));

    patchStatus(blocked, TaskStatus.DONE).andExpect(status().isOk());

    assertStatus(blocked, TaskStatus.DONE);
  }

  @Test
  void R3_openCrossProjectBlockerRejectsDoneWithItsOwnKey() throws Exception {
    Task blocked = create(project, "Blocked");
    Project other = inWorkspace(() -> projectService.createProject("OTH", "Other project"));
    Task blocker = create(other, "Cross project prerequisite");
    linkFixture(blocked, blocker);
    JsonNode beforeTask = persistedTask(blocked);
    Counts before = counts();

    ResultActions result = patchStatus(blocked, TaskStatus.DONE).andExpect(status().isBadRequest());

    assertEquals(blockedDetail(blocked, List.of(key(other, blocker))), detail(result));
    assertEquals(beforeTask, persistedTask(blocked));
    assertEquals(before, counts());
  }

  @Test
  void K1_R3_doneCrossProjectBlockerAllowsDone() throws Exception {
    Task blocked = create(project, "Blocked");
    Project other = inWorkspace(() -> projectService.createProject("OTH", "Other project"));
    Task blocker = create(other, "Finished cross project prerequisite");
    setStatus(blocker, TaskStatus.DONE);
    linkFixture(blocked, blocker);

    patchStatus(blocked, TaskStatus.DONE).andExpect(status().isOk());

    assertStatus(blocked, TaskStatus.DONE);
  }

  @Test
  void K4_bulkDoneCompletesEveryUnblockedTask() throws Exception {
    Task independent = create(project, "Independent");
    Task blockedByDone = create(project, "Finished prerequisite only");
    Task blockedByDeleted = create(project, "Deleted prerequisite only");
    Task done = create(project, "Done prerequisite");
    Task deleted = create(project, "Deleted prerequisite");
    setStatus(done, TaskStatus.DONE);
    linkFixture(blockedByDone, done);
    linkFixture(blockedByDeleted, deleted);
    softDelete(deleted);
    Counts before = counts();
    List<Task> tasks = List.of(independent, blockedByDone, blockedByDeleted);

    ResultActions result = bulkDone(tasks).andExpect(status().isOk());

    assertEquals(3, response(result).size());
    tasks.forEach(task -> assertStatus(task, TaskStatus.DONE));
    assertEquals(new Counts(before.audit() + 3, before.outbox() + 3), counts());
  }

  @Test
  void R4_bulkBlockedTaskRollsBackEarlierTaskAndAllEventWrites() throws Exception {
    Task first = create(project, "Must roll back");
    Task blocked = create(project, "Blocked second");
    Task last = create(project, "Must remain untouched");
    Task blocker = create(project, "Open prerequisite");
    setStatus(first, TaskStatus.REVIEW);
    setStatus(last, TaskStatus.IN_PROGRESS);
    linkFixture(blocked, blocker);
    List<Task> tasks = List.of(first, blocked, last);
    List<JsonNode> beforeTasks = tasks.stream().map(this::persistedTask).toList();
    Counts before = counts();

    ResultActions result = bulkDone(tasks).andExpect(status().isBadRequest());

    assertEquals(blockedDetail(blocked, List.of(key(project, blocker))), detail(result));
    assertEquals(beforeTasks, tasks.stream().map(this::persistedTask).toList());
    assertEquals(before, counts(), "R4: ilk gorevin audit/outbox yazimi da geri alinmali");
  }

  @Test
  void K5_unblockedPullRequestMergeAdvancesTaskToDone() {
    Task task = create(project, "Unblocked merge");
    setStatus(task, TaskStatus.REVIEW);
    Counts before = counts();

    int advanced = assertDoesNotThrow(() -> merge(UUID.randomUUID(), key(project, task)));

    assertEquals(1, advanced);
    assertStatus(task, TaskStatus.DONE);
    assertEquals(new Counts(before.audit() + 1, before.outbox() + 1), counts());
    assertTrue(outboxRows(task, BLOCKED_EVENT).isEmpty());
  }

  @Test
  void R5_blockedMergeRecordsOneOutboxEventAndMarksDeliveryProcessed() {
    Task blocked = create(project, "Blocked merge");
    Task blocker = create(project, "Open prerequisite");
    setStatus(blocked, TaskStatus.REVIEW);
    linkFixture(blocked, blocker);
    UUID eventId = UUID.randomUUID();
    JsonNode beforeTask = persistedTask(blocked);
    Counts before = counts();

    assertEquals(0, assertDoesNotThrow(() -> merge(eventId, key(project, blocked))));

    assertEquals(beforeTask, persistedTask(blocked));
    assertEquals(new Counts(before.audit(), before.outbox() + 1), counts());
    assertEquals(1L, processedCount(eventId));
    List<OutboxRow> rows = outboxRows(blocked, BLOCKED_EVENT);
    assertEquals(1, rows.size());
    assertBlockedOutbox(rows.get(0), blocked, List.of(key(project, blocker)));
    Counts afterFirst = counts();

    assertEquals(0, assertDoesNotThrow(() -> merge(eventId, key(project, blocked))));

    assertEquals(afterFirst, counts());
    assertEquals(rows, outboxRows(blocked, BLOCKED_EVENT));
    assertEquals(beforeTask, persistedTask(blocked));
    // Engel kalktiktan sonra da ayni eventId atlanmali: yalniz ileri-gecis korumasi yetmez.
    setStatus(blocker, TaskStatus.DONE);
    Counts afterUnblock = counts();
    assertEquals(0, assertDoesNotThrow(() -> merge(eventId, key(project, blocked))));
    assertEquals(afterUnblock, counts());
    assertStatus(blocked, TaskStatus.REVIEW);
    assertEquals(1L, processedCount(eventId));
    assertEquals(rows, outboxRows(blocked, BLOCKED_EVENT));

    assertEquals(1, assertDoesNotThrow(() -> merge(UUID.randomUUID(), key(project, blocked))));
    assertStatus(blocked, TaskStatus.DONE);
  }

  @Test
  void K5_R5_mergeSkipsBlockedReferenceAndStillAdvancesUnblockedReference() {
    Task blocked = create(project, "Blocked reference first");
    Task unblocked = create(project, "Unblocked reference second");
    Task blocker = create(project, "Open prerequisite");
    linkFixture(blocked, blocker);

    int advanced =
        assertDoesNotThrow(
            () -> merge(UUID.randomUUID(), key(project, blocked) + " " + key(project, unblocked)));

    assertEquals(1, advanced);
    assertStatus(blocked, TaskStatus.TO_DO);
    assertStatus(unblocked, TaskStatus.DONE);
    assertEquals(1, outboxRows(blocked, BLOCKED_EVENT).size());
    assertTrue(outboxRows(unblocked, BLOCKED_EVENT).isEmpty());
  }

  @Test
  void K5_R5_mergeOfAlreadyDoneTaskDoesNotRecordBlockedTransition() {
    Task blocked = create(project, "Already done before merge");
    Task blocker = create(project, "Reopened prerequisite");
    setStatus(blocker, TaskStatus.DONE);
    linkFixture(blocked, blocker);
    setStatus(blocked, TaskStatus.DONE);
    setStatus(blocker, TaskStatus.TO_DO);
    Counts before = counts();
    UUID eventId = UUID.randomUUID();

    assertEquals(0, assertDoesNotThrow(() -> merge(eventId, key(project, blocked))));

    assertStatus(blocked, TaskStatus.DONE);
    assertEquals(before, counts());
    assertEquals(1L, processedCount(eventId));
    assertTrue(outboxRows(blocked, BLOCKED_EVENT).isEmpty());
  }

  @Test
  void K6_realBlockedOutboxNotifiesAssigneeAndWatcherExactlyOnce() {
    Member assignee = member(WorkspaceRole.DEVELOPER);
    Member outsider = member(WorkspaceRole.DEVELOPER);
    Task blocked = create(project, "Waiting for prerequisite");
    Task blocker = create(project, "Open prerequisite");
    linkFixture(blocked, blocker);
    inWorkspace(() -> taskService.assign(blocked.getId(), assignee.id(), manager.id()));
    assertTrue(
        inWorkspace(() -> taskService.getDetail(blocked.getId()))
            .watcherIds()
            .contains(assignee.id()));
    assertEquals(0, merge(UUID.randomUUID(), key(project, blocked)));
    OutboxRow row = onlyBlockedOutbox(blocked);
    assertBlockedOutbox(row, blocked, List.of(key(project, blocker)));
    assertTrue(blockedNotifications(assignee, blocked).isEmpty());

    assertDoesNotThrow(() -> inboxConsumer.onMessage(envelope(row, payload(row))));

    assertNotification(assignee, blocked, List.of(key(project, blocker)));
    assertNotification(manager, blocked, List.of(key(project, blocker)));
    assertTrue(blockedNotifications(outsider, blocked).isEmpty());
    assertDoesNotThrow(() -> inboxConsumer.onMessage(envelope(row, payload(row))));
    assertNotification(assignee, blocked, List.of(key(project, blocker)));
    assertNotification(manager, blocked, List.of(key(project, blocker)));
    assertStatus(blocked, TaskStatus.TO_DO);
  }

  @Test
  void K6_watcherActorIsExcludedWhileAssigneeStillReceivesNotification() {
    Member assignee = member(WorkspaceRole.DEVELOPER);
    Task blocked = create(project, "Actor exclusion");
    Task blocker = create(project, "Open prerequisite");
    linkFixture(blocked, blocker);
    inWorkspace(() -> taskService.assign(blocked.getId(), assignee.id(), manager.id()));
    assertEquals(0, merge(UUID.randomUUID(), key(project, blocked)));
    OutboxRow row = onlyBlockedOutbox(blocked);
    // Fan-out fiksturu: olayin aktoru ayni zamanda izleyici. GitHub aktoru ayrica R5'te dogrulanir.
    ObjectNode watcherActorPayload = (ObjectNode) payload(row);
    watcherActorPayload.put("actorId", manager.id().toString());

    assertDoesNotThrow(() -> inboxConsumer.onMessage(envelope(row, watcherActorPayload)));

    assertTrue(blockedNotifications(manager, blocked).isEmpty());
    assertNotification(assignee, blocked, List.of(key(project, blocker)));
  }

  @Test
  void K6_missingOrMalformedBlockerKeysProduceNoFormattedMessage() {
    TaskSummary summary = new TaskSummary("DEP", 1, "Blocked");
    for (String json :
        List.of(
            "{}",
            "{\"blockerKeys\":null}",
            "{\"blockerKeys\":\"X-2\"}",
            "{\"blockerKeys\":7}",
            "{\"blockerKeys\":true}",
            "{\"blockerKeys\":{\"key\":\"X-2\"}}",
            "{\"blockerKeys\":[\"X-2\",7]}",
            "{\"blockerKeys\":[\"X-2\",null]}",
            "{\"blockerKeys\":[\"X-2\",\"\"]}",
            "{\"blockerKeys\":[\"X-2\",\"   \"]}")) {
      assertTrue(
          NotificationMessageFormatter.format(BLOCKED_EVENT, objectMapper.readTree(json), summary)
              .isEmpty(),
          json);
    }
    assertEquals(BLOCKED_EVENT, NotificationMessageFormatter.TASK_TRANSITION_BLOCKED);
    assertTrue(NotificationMessageFormatter.isNotifiable(BLOCKED_EVENT));
    String[] message =
        NotificationMessageFormatter.format(
                BLOCKED_EVENT,
                objectMapper.readTree("{\"blockerKeys\":[\"X-2\",\"X-10\",\"Y-7\"]}"),
                summary)
            .orElseThrow();
    assertEquals("DEP-1: Blocked", message[0]);
    assertEquals(blockedBody(List.of("X-2", "X-10", "Y-7")), message[1]);
  }

  @Test
  void K6b_longBlockerListStillReachesAssigneeInbox() {
    Member assignee = member(WorkspaceRole.DEVELOPER);
    Task blocked = create(project, "Many prerequisites");
    inWorkspace(() -> taskService.assign(blocked.getId(), assignee.id(), manager.id()));
    List<String> blockerKeys = new ArrayList<>();
    for (int number = 0; number < 80; number++) {
      Task blocker = create(project, "Prerequisite " + number);
      linkFixture(blocked, blocker);
      blockerKeys.add(key(project, blocker));
    }
    assertTrue(blockedBody(blockerKeys).length() > 500, "Uzun mesaj siniri gercekten asilmali");
    assertEquals(0, assertDoesNotThrow(() -> merge(UUID.randomUUID(), key(project, blocked))));
    OutboxRow row = onlyBlockedOutbox(blocked);
    assertBlockedOutbox(row, blocked, blockerKeys);

    assertDoesNotThrow(() -> inboxConsumer.onMessage(envelope(row, payload(row))));

    List<Notification> notifications = blockedNotifications(assignee, blocked);
    assertEquals(1, notifications.size());
    String body = notifications.get(0).getBody();
    assertTrue(body.length() <= 500, "K6b: Inbox govdesi 500 karakteri asmamali");
    List<String> visibleKeys =
        Pattern.compile("\\b" + Pattern.quote(project.getKey()) + "-\\d+\\b")
            .matcher(body)
            .results()
            .map(match -> match.group())
            .toList();
    assertTrue(visibleKeys.contains(blockerKeys.get(0)), "K6b: ilk bloklayan anahtari gorunmeli");
    Matcher remaining = Pattern.compile(" ve ([1-9][0-9]*) gorev daha$").matcher(body);
    assertTrue(remaining.find(), "K6b: sigmayan gorevler sayilariyla ozetlenmeli");
    assertEquals(80, visibleKeys.size() + Integer.parseInt(remaining.group(1)));
    assertEquals(blockerKeys.subList(0, visibleKeys.size()), visibleKeys);
  }

  @Test
  void K7_doneTaskCanLinkDoneBlockerOverHttp() throws Exception {
    Task blocked = create(project, "Done task");
    Task blocker = create(project, "Done prerequisite");
    setStatus(blocked, TaskStatus.DONE);
    setStatus(blocker, TaskStatus.DONE);

    linkHttp(blocked, blocker).andExpect(status().isOk());

    assertLinked(blocked, blocker);
    assertStatus(blocked, TaskStatus.DONE);
  }

  @ParameterizedTest(name = "R7 Done task rejects blocker in {0}")
  @ValueSource(strings = {"To Do", "In Progress", "Review"})
  void R7_doneTaskCannotLinkOpenBlockerOverHttp(String blockerStatus) throws Exception {
    Task blocked = create(project, "Done task");
    Task blocker = create(project, "Open prerequisite");
    setStatus(blocked, TaskStatus.DONE);
    if (!TaskStatus.TO_DO.equals(blockerStatus)) {
      setStatus(blocker, blockerStatus);
    }
    JsonNode beforeTask = persistedTask(blocked);
    Counts before = counts();

    ResultActions result = linkHttp(blocked, blocker).andExpect(status().isBadRequest());

    assertEquals("Tamamlanmis bir goreve acik bir bloklayan eklenemez.", detail(result));
    assertEquals(0L, dependencyCount(blocked, blocker));
    assertTrue(
        inWorkspace(() -> dependencyService.dependenciesForTask(blocked.getId()))
            .blockedBy()
            .isEmpty());
    assertEquals(beforeTask, persistedTask(blocked));
    assertEquals(before, counts());
  }

  @Test
  void K8_openTaskCanLinkOpenBlockerOverHttp() throws Exception {
    Task blocked = create(project, "Open task");
    Task blocker = create(project, "Open prerequisite");

    linkHttp(blocked, blocker).andExpect(status().isOk());

    assertLinked(blocked, blocker);
    assertStatus(blocked, TaskStatus.TO_DO);
    assertStatus(blocker, TaskStatus.TO_DO);
  }

  private Member member(String role) {
    String email = "dependency-" + UUID.randomUUID() + "@tracker.local";
    UUID id =
        authService
            .register(email, PASSWORD, "Dependency " + role, PrivacyNotice.CURRENT_VERSION)
            .getId();
    membershipService.addMember(workspaceId, id, role);
    return new Member(id, authService.login(email, PASSWORD, "127.0.0.1").accessToken());
  }

  private Task create(Project owner, String title) {
    return inWorkspace(() -> taskService.createTask(owner.getId(), title, null, manager.id()));
  }

  private void setStatus(Task task, String target) {
    inWorkspace(() -> taskService.updateStatus(task.getId(), target, manager.id()));
    assertStatus(task, target);
  }

  private void softDelete(Task task) {
    inWorkspace(() -> taskService.deleteTask(task.getId(), manager.id()));
    assertEquals(
        1L,
        count("SELECT COUNT(*) FROM tasks WHERE id = ?1 AND deleted_at IS NOT NULL", task.getId()));
  }

  private void linkFixture(Task blocked, Task blocker) {
    inWorkspace(() -> dependencyService.link(blocked.getId(), blocker.getId(), manager.id()));
    assertLinked(blocked, blocker);
  }

  private void assertLinked(Task blocked, Task blocker) {
    assertEquals(1L, dependencyCount(blocked, blocker));
    assertTrue(
        inWorkspace(() -> dependencyService.dependenciesForTask(blocked.getId()))
            .blockedBy()
            .stream()
            .anyMatch(ref -> ref.id().equals(blocker.getId())));
  }

  private long dependencyCount(Task blocked, Task blocker) {
    return count(
        "SELECT COUNT(*) FROM task_dependencies WHERE blocked_task_id = ?1 AND blocking_task_id = ?2",
        blocked.getId(),
        blocker.getId());
  }

  private ResultActions patchStatus(Task task, String target) throws Exception {
    return mockMvc.perform(
        patch("/api/v1/tasks/{id}", task.getId())
            .header("Authorization", "Bearer " + manager.token())
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new UpdateTaskStatusRequest(target))));
  }

  private ResultActions bulkDone(List<Task> tasks) throws Exception {
    BulkTaskRequest request =
        new BulkTaskRequest(
            tasks.stream().map(Task::getId).toList(),
            BulkOperation.STATUS,
            TaskStatus.DONE,
            null,
            null,
            null);
    return mockMvc.perform(
        post("/api/v1/tasks/bulk")
            .header("Authorization", "Bearer " + manager.token())
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }

  private ResultActions linkHttp(Task blocked, Task blocker) throws Exception {
    return mockMvc.perform(
        put("/api/v1/tasks/{id}/dependencies/{blockingId}", blocked.getId(), blocker.getId())
            .header("Authorization", "Bearer " + manager.token())
            .header("X-Workspace-Id", workspaceId.toString()));
  }

  private JsonNode response(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private String detail(ResultActions result) throws Exception {
    return response(result).path("detail").asString();
  }

  private String key(Project owner, Task task) {
    return owner.getKey() + "-" + task.getTaskNumber();
  }

  private String blockedDetail(Task task, List<String> blockerKeys) {
    return key(project, task)
        + " tamamlanamaz; once bitmesi gereken gorevler: "
        + String.join(", ", blockerKeys);
  }

  private String blockedBody(List<String> blockerKeys) {
    return "PR merge edildi ama gorev Done'a tasinmadi; once bitmesi gereken: "
        + String.join(", ", blockerKeys);
  }

  private JsonNode persistedTask(Task task) {
    return objectMapper.valueToTree(inWorkspace(() -> taskService.getDetail(task.getId())).task());
  }

  private void assertStatus(Task task, String expected) {
    assertEquals(
        expected, inWorkspace(() -> taskService.getDetail(task.getId())).task().getStatus());
  }

  private Counts counts() {
    return new Counts(
        count(
            "SELECT COUNT(*) FROM task_events e JOIN tasks t ON t.id = e.task_id WHERE t.workspace_id = ?1",
            workspaceId),
        count(
            "SELECT COUNT(*) FROM outbox_events WHERE workspace_id = ?1 AND topic = 'task.events'",
            workspaceId));
  }

  private long count(String sql, Object... parameters) {
    return inDb(
        () -> {
          Query query = entityManager.createNativeQuery(sql);
          for (int index = 0; index < parameters.length; index++) {
            query.setParameter(index + 1, parameters[index]);
          }
          return ((Number) query.getSingleResult()).longValue();
        });
  }

  private long processedCount(UUID eventId) {
    return count(
        "SELECT COUNT(*) FROM processed_events WHERE consumer = ?1 AND event_id = ?2",
        GithubEventProcessor.CONSUMER,
        eventId);
  }

  private int merge(UUID eventId, String references) {
    ObjectNode pr = objectMapper.createObjectNode();
    pr.put("merged", true);
    pr.put("draft", false);
    pr.put("title", references + " implement feature");
    pr.put("body", "");
    pr.set("head", objectMapper.createObjectNode().put("ref", "feature"));
    ObjectNode body = objectMapper.createObjectNode();
    body.put("action", "closed");
    body.set("pull_request", pr);
    return inWorkspace(
        () ->
            githubEventProcessor.process(
                eventId, "pull_request", objectMapper.writeValueAsString(body)));
  }

  @SuppressWarnings("unchecked")
  private List<OutboxRow> outboxRows(Task task, String eventType) {
    return inDb(
        () -> {
          List<Object[]> rows =
              entityManager
                  .createNativeQuery(
                      "SELECT id, topic, event_type, schema_version, aggregate_id, workspace_id, "
                          + "payload::text, created_at FROM outbox_events "
                          + "WHERE workspace_id = ?1 AND aggregate_id = ?2 AND event_type = ?3 "
                          + "ORDER BY created_at, id")
                  .setParameter(1, workspaceId)
                  .setParameter(2, task.getId())
                  .setParameter(3, eventType)
                  .getResultList();
          return rows.stream()
              .map(
                  row ->
                      new OutboxRow(
                          (UUID) row[0],
                          (String) row[1],
                          (String) row[2],
                          ((Number) row[3]).intValue(),
                          (UUID) row[4],
                          (UUID) row[5],
                          (String) row[6],
                          (Instant) row[7]))
              .toList();
        });
  }

  private OutboxRow onlyBlockedOutbox(Task task) {
    List<OutboxRow> rows = outboxRows(task, BLOCKED_EVENT);
    assertEquals(1, rows.size());
    return rows.get(0);
  }

  private JsonNode payload(OutboxRow row) {
    return objectMapper.readTree(row.payloadJson());
  }

  private void assertBlockedOutbox(OutboxRow row, Task task, List<String> blockerKeys) {
    assertEquals("task.events", row.topic());
    assertEquals(BLOCKED_EVENT, row.eventType());
    assertEquals(1, row.schemaVersion());
    assertEquals(task.getId(), row.aggregateId());
    assertEquals(workspaceId, row.workspaceId());
    JsonNode payload = payload(row);
    assertEquals(task.getId().toString(), payload.path("taskId").asString());
    assertEquals(task.getProjectId().toString(), payload.path("projectId").asString());
    assertEquals(TaskStatus.DONE, payload.path("targetStatus").asString());
    assertEquals(IntegrationActor.SYSTEM_USER_ID.toString(), payload.path("actorId").asString());
    assertEquals("GITHUB_PR_MERGE", payload.path("source").asString());
    assertTrue(payload.path("blockerKeys").isArray());
    assertEquals(objectMapper.valueToTree(blockerKeys), payload.path("blockerKeys"));
  }

  private String envelope(OutboxRow row, JsonNode payload) {
    ObjectNode envelope = objectMapper.createObjectNode();
    envelope.put("eventId", row.id().toString());
    envelope.put("eventType", row.eventType());
    envelope.put("schemaVersion", row.schemaVersion());
    envelope.put("timestamp", row.createdAt().toString());
    envelope.put("aggregateId", row.aggregateId().toString());
    envelope.put("workspaceId", row.workspaceId().toString());
    envelope.set("payload", payload);
    return objectMapper.writeValueAsString(envelope);
  }

  private List<Notification> blockedNotifications(Member recipient, Task task) {
    return inWorkspace(() -> notificationService.listMine(recipient.id(), 100, null, false))
        .data()
        .stream()
        .filter(notification -> BLOCKED_EVENT.equals(notification.getType()))
        .filter(notification -> task.getId().equals(notification.getTaskId()))
        .toList();
  }

  private void assertNotification(Member recipient, Task task, List<String> blockerKeys) {
    List<Notification> notifications = blockedNotifications(recipient, task);
    assertEquals(1, notifications.size());
    Notification notification = notifications.get(0);
    assertEquals(recipient.id(), notification.getUserId());
    assertEquals(workspaceId, notification.getWorkspaceId());
    assertEquals(task.getProjectId(), notification.getProjectId());
    assertEquals(key(project, task) + ": " + task.getTitle(), notification.getTitle());
    assertEquals(blockedBody(blockerKeys), notification.getBody());
    assertTrue(notification.isUnread());
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  private void inWorkspace(Runnable action) {
    tenantExecutor.runAs(workspaceId, action);
  }

  private <T> T inDb(Supplier<T> action) {
    // TransactionTemplate servis AOP'sinden gecmez: RLS GUC'u acikca bu transaction'a baglanir.
    return inWorkspace(
        () ->
            transactionTemplate.execute(
                tx -> {
                  entityManager
                      .createNativeQuery("SELECT set_config('app.current_workspace_id', ?1, true)")
                      .setParameter(1, workspaceId.toString())
                      .getSingleResult();
                  return action.get();
                }));
  }
}
