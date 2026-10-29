package com.app.tracker.task;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.sprint.service.SprintService;
import com.app.tracker.tag.service.TagService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dilim 07: beklentiler sozlesmeden, duzenek mevcut HTTP testlerinden alinmistir. Gercek rol
 * token'lari ve paylasilan Spring context kullanilir. Test transaction'i yoktur; ret sonrasi
 * sorgular commit/rollback sonucunu ayri transaction'da okur. Bu ortamda Maven/Docker yoktur;
 * calistirilmamis sok-kir plani .dilim/dilim-07-sok-kir-plani.md dosyasindadir.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubtaskCreationIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthService authService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private SprintService sprintService;
  @Autowired private TagService tagService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private Clock clock;

  private UUID workspaceId;
  private Project project;
  private Member manager;
  private Member developer;
  private Member otherDeveloper;
  private Member viewer;

  private record Member(UUID id, String token) {}

  private record Counts(long tasks, long lastNumber, long outbox) {}

  private record StoredTask(
      UUID projectId,
      UUID workspaceId,
      UUID parentTaskId,
      UUID assigneeId,
      UUID sprintId,
      Integer storyPoint,
      String title,
      String dueDate,
      String description) {}

  @FunctionalInterface
  private interface HttpCall {
    ResultActions perform() throws Exception;
  }

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Subtask WS"));
    manager = member(WorkspaceRole.MANAGER);
    developer = member(WorkspaceRole.DEVELOPER);
    otherDeveloper = member(WorkspaceRole.DEVELOPER);
    viewer = member(WorkspaceRole.VIEWER);
    project = inWorkspace(() -> projectService.createProject("SUB", "Subtask creation"));
  }

  @Test
  void K1_developerCreatesTitleOnlyChildWithOwnAssignmentAndActiveSprint() throws Exception {
    UUID parent = root(developer.id());
    UUID sprint = activeSprint(parent);
    ObjectNode request = titleOnly();
    Counts before = counts();

    JsonNode child = createAccepted(developer, parent, request);

    assertChild(child, parent, developer.id(), sprint, request);
    assertEquals(
        new Counts(before.tasks() + 1, before.lastNumber() + 1, before.outbox() + 4), counts());
    assertEquals(
        List.of("TASK_CREATED", "TASK_PARENT_CHANGED", "TASK_ASSIGNED", "TASK_SPRINT_CHANGED"),
        eventTypes(id(child)));
  }

  @Test
  void R1_developerCannotCreateUnderAnotherMembersTaskBeforeCheckingNesting() throws Exception {
    UUID ownParent = root(developer.id());
    createAccepted(developer, ownParent, titleOnly());
    UUID otherParent = root(otherDeveloper.id());
    ObjectNode request = titleOnly();
    assertRejectedWithoutWrites(() -> create(developer, otherParent, request), 403);

    // Sozlesmedeki sira: sahiplik kontrolu, ustun alt gorev olmasi kontrolunden ONCE gelir.
    // Bu ikinci probe, sahiplik kaldirilinca genel atama kontrolunun 403'u maskelemesini onler.
    UUID nested = id(createAccepted(manager, otherParent, titleOnly()));
    assertRejectedWithoutWrites(() -> create(developer, nested, request), 403);
  }

  @Test
  void R1b_developerCannotCreateUnderUnassignedTaskWhileManagerCan() throws Exception {
    UUID parent = root(null);
    ObjectNode request = titleOnly();
    assertChild(createAccepted(manager, parent, request), parent, null, null, request);

    assertRejectedWithoutWrites(() -> create(developer, parent, request), 403);
  }

  @Test
  void R1c_developerCanExplicitlyKeepSelfButCannotChooseAnotherMember() throws Exception {
    UUID parent = root(developer.id());
    ObjectNode request = titleOnly().put("assigneeId", developer.id().toString());
    assertChild(createAccepted(developer, parent, request), parent, developer.id(), null, request);
    request.put("assigneeId", otherDeveloper.id().toString());

    assertRejectedWithoutWrites(() -> create(developer, parent, request), 403);
  }

  @ParameterizedTest(name = "K2 manager overrides assignee of {0} parent")
  @ValueSource(strings = {"UNASSIGNED", "SELF", "OTHER"})
  void K2_managerCanChooseAnotherMemberRegardlessOfParentAssignee(String initial) throws Exception {
    UUID assignee =
        switch (initial) {
          case "UNASSIGNED" -> null;
          case "SELF" -> manager.id();
          case "OTHER" -> developer.id();
          default -> throw new IllegalArgumentException(initial);
        };
    UUID parent = root(assignee);
    ObjectNode request = titleOnly().put("assigneeId", otherDeveloper.id().toString());

    assertChild(
        createAccepted(manager, parent, request), parent, otherDeveloper.id(), null, request);
  }

  @Test
  void R2_viewerCannotCreateWhileManagerAcceptsIdenticalRequest() throws Exception {
    // Atanmamis ust: genel atama kontrolu anotasyon sok-kirinda yeni bir 403 uretmemeli.
    UUID parent = root(null);
    ObjectNode request = titleOnly();
    assertChild(createAccepted(manager, parent, request), parent, null, null, request);

    assertRejectedWithoutWrites(() -> create(viewer, parent, request), 403);
    // Anotasyon kaldirilirsa olmayan ust icin servis 404 verir;
    // sahiplik kontrolu 403'u maskeleyemez.
    UUID missingParent = UUID.randomUUID();
    assertRejectedWithoutWrites(() -> create(viewer, missingParent, request), 403);
  }

  @Test
  void R3_rootAcceptsChildButChildCannotAcceptGrandchild() throws Exception {
    UUID parent = root(null);
    UUID child = id(createAccepted(manager, parent, titleOnly()));
    ObjectNode request = titleOnly();
    assertChild(createAccepted(manager, parent, request), parent, null, null, request);

    JsonNode error =
        response(assertRejectedWithoutWrites(() -> create(manager, child, request), 400));
    // setParent da 400 verebilir. Sozlesmedeki metin, erken createSubtask korumasini ayirir.
    assertEquals("Alt gorevin altina alt gorev acilamaz.", error.path("detail").asString());
  }

  @Test
  void R3b_unapprovedParentAcceptsButApprovedParentRejects() throws Exception {
    UUID parent = root(null);
    ObjectNode request = titleOnly();
    createAccepted(manager, parent, request);
    inWorkspace(() -> taskService.updateStatus(parent, TaskStatus.DONE, manager.id()));
    inWorkspace(() -> taskService.approve(parent, manager.id()));

    assertRejectedWithoutWrites(() -> create(manager, parent, request), 400);
  }

  @Test
  void K4_completedParentSprintIsNotCopiedToChild() throws Exception {
    UUID parent = root(developer.id());
    UUID sprint = activeSprint(parent);
    inWorkspace(() -> sprintService.completeSprint(sprint));
    assertEquals(sprint, storedTask(parent).sprintId());
    ObjectNode request = titleOnly();

    JsonNode child = createAccepted(developer, parent, request);

    assertChild(child, parent, developer.id(), null, request);
    assertEquals(sprint, storedTask(parent).sprintId());
    assertEquals(
        List.of("TASK_CREATED", "TASK_PARENT_CHANGED", "TASK_ASSIGNED"), eventTypes(id(child)));
  }

  @Test
  void R5_storyPointThreeAcceptedOnRootButRejectedOnChildWithoutWrites() throws Exception {
    UUID parent = root(null);
    ObjectNode points = objectMapper.createObjectNode().put("storyPoint", 3);
    response(update(parent, "story-point", points).andExpect(status().isOk()));
    assertEquals(Integer.valueOf(3), storedTask(parent).storyPoint());
    UUID child = id(createAccepted(manager, parent, titleOnly()));
    StoredTask before = storedTask(child);

    JsonNode error =
        response(assertRejectedWithoutWrites(() -> update(child, "story-point", points), 400));

    assertEquals(
        "Alt goreve story point verilemez; puan ust gorevde tutulur.",
        error.path("detail").asString());
    assertEquals(before, storedTask(child));
    assertEquals(Integer.valueOf(3), storedTask(parent).storyPoint());
  }

  @Test
  void R5_nullRemovesLegacyChildPointsAndKeepsParentRelationship() throws Exception {
    UUID parent = root(null);
    UUID child = id(createAccepted(manager, parent, titleOnly()));
    // Sozlesme mevcut puanli alt gorevleri geriye donuk duzeltmez. Bu veri servisle artik
    // olusturulamaz; yalniz legacy fiksturu icin native yazma, sinanan kaldirma HTTP'dendir.
    inDb(
        () ->
            query(
                    "UPDATE tasks SET custom_fields = "
                        + "jsonb_set(COALESCE(custom_fields, '{}'::jsonb), "
                        + "'{story_point}', '3'::jsonb) "
                        + "WHERE id = ?1",
                    child)
                .executeUpdate());
    assertEquals(Integer.valueOf(3), storedTask(child).storyPoint());
    ObjectNode request = objectMapper.createObjectNode().putNull("storyPoint");

    JsonNode updated = response(update(child, "story-point", request).andExpect(status().isOk()));

    assertTrue(updated.has("storyPoint"));
    assertTrue(updated.path("storyPoint").isNull());
    assertEquals(parent.toString(), updated.path("parentTaskId").asString());
    assertNull(storedTask(child).storyPoint());
    assertEquals(parent, storedTask(child).parentTaskId());
  }

  @Test
  void K5_parentCanReceiveStoryPointsWithExistingChild() throws Exception {
    UUID parent = root(null);
    UUID child = id(createAccepted(manager, parent, titleOnly()));
    ObjectNode request = objectMapper.createObjectNode().put("storyPoint", 3);

    JsonNode updated = response(update(parent, "story-point", request).andExpect(status().isOk()));

    assertEquals(3, updated.path("storyPoint").asInt());
    assertEquals(Integer.valueOf(3), storedTask(parent).storyPoint());
    assertNull(storedTask(parent).parentTaskId());
    assertNull(storedTask(child).storyPoint());
    assertEquals(parent, storedTask(child).parentTaskId());
  }

  @Test
  void R6_pointedTaskCannotBeAttachedAndBothPointsAndRelationshipStayUnchanged() throws Exception {
    UUID parent = root(null);
    UUID unpointed = root(null);
    attach(unpointed, parent).andExpect(status().isOk());
    assertEquals(parent, storedTask(unpointed).parentTaskId());
    UUID pointed = root(null);
    update(pointed, "story-point", objectMapper.createObjectNode().put("storyPoint", 3))
        .andExpect(status().isOk());
    StoredTask taskBefore = storedTask(pointed);
    StoredTask parentBefore = storedTask(parent);
    assertEquals(Integer.valueOf(3), taskBefore.storyPoint());
    assertNull(taskBefore.parentTaskId());

    JsonNode error = response(assertRejectedWithoutWrites(() -> attach(pointed, parent), 400));

    assertEquals(
        "Puanli bir gorev alt gorev yapilamaz; once puani kaldirin.",
        error.path("detail").asString());
    assertAll(
        () -> assertEquals(taskBefore, storedTask(pointed)),
        () -> assertEquals(parentBefore, storedTask(parent)),
        () -> assertEquals(Integer.valueOf(3), storedTask(pointed).storyPoint()),
        () -> assertNull(storedTask(pointed).parentTaskId()));
  }

  @Test
  void K6_managerCanAttachUnpointedTask() throws Exception {
    UUID parent = root(null);
    UUID child = root(null);

    JsonNode updated = response(attach(child, parent).andExpect(status().isOk()));

    assertEquals(parent.toString(), updated.path("parentTaskId").asString());
    assertEquals(parent, storedTask(child).parentTaskId());
    assertNull(storedTask(child).storyPoint());
  }

  @Test
  void R7_foreignTagRollsBackChildCounterOutboxAndEarlierValidTag() throws Exception {
    UUID parent = root(developer.id());
    UUID sprint = activeSprint(parent);
    UUID validTag = inWorkspace(() -> tagService.createTag("Local", "#123456").getId());
    UUID otherWorkspace = UUID.randomUUID();
    tenantExecutor.runAs(
        null, () -> workspaceService.createWorkspace(otherWorkspace, "Foreign tags"));
    UUID foreignTag =
        tenantExecutor.runAs(
            otherWorkspace, () -> tagService.createTag("Foreign", "#abcdef").getId());
    ObjectNode request =
        titleOnly()
            .put("description", "Subtask rollback probe")
            .put("dueDate", futureDate().toString())
            .put("assigneeId", developer.id().toString());
    request.set("tagIds", objectMapper.valueToTree(List.of(validTag)));
    JsonNode accepted = createAccepted(manager, parent, request);
    assertChild(accepted, parent, developer.id(), sprint, request);
    assertEquals(List.of(validTag), storedTags(id(accepted)));
    assertEquals(validTag.toString(), accepted.path("tags").get(0).path("id").asString());
    long tagsBefore = taskTagCount();
    StoredTask parentBefore = storedTask(parent);
    request.set("tagIds", objectMapper.valueToTree(List.of(validTag, foreignTag)));

    assertRejectedWithoutWrites(() -> create(manager, parent, request), 0);

    assertEquals(tagsBefore, taskTagCount());
    assertEquals(parentBefore, storedTask(parent));
    assertEquals(List.of(validTag), storedTags(id(accepted)));
  }

  @ParameterizedTest(name = "K1 request validation rejects {0} with 422 and accepts its boundary")
  @ValueSource(
      strings = {"MISSING_TITLE", "BLANK_TITLE", "TITLE_256", "DESCRIPTION_20001", "TAGS_21"})
  void K1_requestValidationHasAcceptanceCounterpartAndNoWritesOn422(String invalid)
      throws Exception {
    UUID parent = root(developer.id());
    ObjectNode request = titleOnly();
    List<UUID> tagIds = new ArrayList<>();
    switch (invalid) {
      case "TITLE_256" -> request.put("title", "T".repeat(255));
      case "DESCRIPTION_20001" -> request.put("description", "D".repeat(20000));
      case "TAGS_21" -> {
        for (int index = 0; index < 21; index++) {
          int tagIndex = index;
          tagIds.add(inWorkspace(() -> tagService.createTag("Tag " + tagIndex, "#123456").getId()));
        }
        request.set("tagIds", objectMapper.valueToTree(tagIds.subList(0, 20)));
      }
      default -> {
        // Eksik/bos basligin kabul esi bos olmayan tek karakterdir.
        request.put("title", "S");
      }
    }
    JsonNode accepted = createAccepted(developer, parent, request);
    assertChild(accepted, parent, developer.id(), null, request);
    if (invalid.equals("TAGS_21")) {
      assertEquals(20, storedTags(id(accepted)).size());
    }
    switch (invalid) {
      case "MISSING_TITLE" -> request.remove("title");
      case "BLANK_TITLE" -> request.put("title", " \t\n ");
      case "TITLE_256" -> request.put("title", "T".repeat(256));
      case "DESCRIPTION_20001" -> request.put("description", "D".repeat(20001));
      case "TAGS_21" -> request.set("tagIds", objectMapper.valueToTree(tagIds));
      default -> throw new IllegalArgumentException(invalid);
    }

    assertRejectedWithoutWrites(() -> create(developer, parent, request), 422);
  }

  private Member member(String role) {
    String email = "subtask-" + UUID.randomUUID() + "@tracker.local";
    UUID userId =
        authService
            .register(email, PASSWORD, "Subtask " + role, PrivacyNotice.CURRENT_VERSION)
            .getId();
    membershipService.addMember(workspaceId, userId, role);
    return new Member(userId, authService.login(email, PASSWORD, "127.0.0.1").accessToken());
  }

  private UUID root(UUID assigneeId) {
    Task task =
        inWorkspace(
            () -> taskService.createTask(project.getId(), "Parent task", null, manager.id()));
    if (assigneeId != null) {
      inWorkspace(() -> taskService.assign(task.getId(), assigneeId, manager.id()));
    }
    assertEquals(assigneeId, storedTask(task.getId()).assigneeId());
    return task.getId();
  }

  private UUID activeSprint(UUID parent) {
    UUID sprintId =
        inWorkspace(
            () ->
                sprintService
                    .createSprint(
                        project.getId(),
                        "Subtask sprint",
                        "Inheritance",
                        futureDate(),
                        futureDate().plusDays(14))
                    .getId());
    inWorkspace(() -> sprintService.startSprint(sprintId));
    inWorkspace(() -> taskService.assignSprint(parent, sprintId, manager.id()));
    assertEquals(sprintId, storedTask(parent).sprintId());
    return sprintId;
  }

  private LocalDate futureDate() {
    return LocalDate.now(clock).plusDays(7);
  }

  private ObjectNode titleOnly() {
    return objectMapper.createObjectNode().put("title", "Subtask " + UUID.randomUUID());
  }

  private ResultActions create(Member actor, UUID parent, ObjectNode request) throws Exception {
    return mockMvc.perform(
        post("/api/v1/tasks/{id}/subtasks", parent)
            .header("Authorization", "Bearer " + actor.token())
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }

  private JsonNode createAccepted(Member actor, UUID parent, ObjectNode request) throws Exception {
    return response(create(actor, parent, request).andExpect(status().isCreated()));
  }

  private ResultActions update(UUID taskId, String endpoint, ObjectNode request) throws Exception {
    return mockMvc.perform(
        put("/api/v1/tasks/{id}/" + endpoint, taskId)
            .header("Authorization", "Bearer " + manager.token())
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }

  private ResultActions attach(UUID child, UUID parent) throws Exception {
    return mockMvc.perform(
        put("/api/v1/tasks/{id}/parent/{parentId}", child, parent)
            .header("Authorization", "Bearer " + manager.token())
            .header("X-Workspace-Id", workspaceId.toString()));
  }

  private JsonNode response(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private UUID id(JsonNode response) {
    return UUID.fromString(response.path("id").asString());
  }

  private void assertChild(
      JsonNode child, UUID parent, UUID assignee, UUID sprint, ObjectNode request) {
    assertFalse(child.path("id").asString().isBlank());
    assertEquals(project.getId().toString(), child.path("projectId").asString());
    assertEquals(parent.toString(), child.path("parentTaskId").asString());
    assertEquals(request.path("title"), child.path("title"));
    assertTrue(child.path("taskNumber").asInt() > 0);
    assertTrue(child.hasNonNull("createdAt"));
    assertUuidField(child, "assigneeId", assignee);
    assertUuidField(child, "sprintId", sprint);
    assertTrue(child.has("storyPoint"));
    assertTrue(child.path("storyPoint").isNull());
    String dueDate = request.hasNonNull("dueDate") ? request.path("dueDate").asString() : null;
    if (dueDate == null) {
      assertTrue(child.path("dueDate").isNull());
    } else {
      assertEquals(dueDate, child.path("dueDate").asString());
    }
    assertEquals(
        new StoredTask(
            project.getId(),
            workspaceId,
            parent,
            assignee,
            sprint,
            null,
            request.path("title").asString(),
            dueDate,
            request.hasNonNull("description") ? request.path("description").asString() : null),
        storedTask(id(child)));
  }

  private void assertUuidField(JsonNode response, String field, UUID expected) {
    assertTrue(response.has(field), field);
    if (expected == null) {
      assertTrue(response.path(field).isNull(), field);
    } else {
      assertEquals(expected.toString(), response.path(field).asString(), field);
    }
  }

  private ResultActions assertRejectedWithoutWrites(HttpCall call, int expectedStatus)
      throws Exception {
    Counts before = counts();
    ResultActions result = call.perform();
    int actualStatus = result.andReturn().getResponse().getStatus();
    Counts after = counts();
    assertAll(
        () -> {
          if (expectedStatus == 0) {
            assertTrue(actualStatus >= 400 && actualStatus < 500, "HTTP status: " + actualStatus);
          } else {
            assertEquals(expectedStatus, actualStatus, "Sozlesmedeki HTTP status");
          }
        },
        () -> assertEquals(before.tasks(), after.tasks(), "Workspace'teki tum gorevler"),
        () -> assertEquals(before.lastNumber(), after.lastNumber(), "task_counters.last_number"),
        () -> assertEquals(before.outbox(), after.outbox(), "Workspace'teki tum outbox satirlari"));
    return result;
  }

  private Counts counts() {
    return inDb(
        () ->
            new Counts(
                number("SELECT COUNT(*) FROM tasks WHERE workspace_id = ?1", workspaceId),
                number(
                    "SELECT last_number FROM task_counters WHERE project_id = ?1", project.getId()),
                number("SELECT COUNT(*) FROM outbox_events WHERE workspace_id = ?1", workspaceId)));
  }

  private StoredTask storedTask(UUID taskId) {
    return inDb(
        () -> {
          Object[] row =
              (Object[])
                  query(
                          "SELECT project_id, workspace_id, parent_task_id, assignee_id, "
                              + "sprint_id, custom_fields ->> 'story_point', title, "
                              + "due_date::text, description "
                              + "FROM tasks WHERE id = ?1",
                          taskId)
                      .getSingleResult();
          return new StoredTask(
              (UUID) row[0],
              (UUID) row[1],
              (UUID) row[2],
              (UUID) row[3],
              (UUID) row[4],
              row[5] == null ? null : Integer.valueOf((String) row[5]),
              (String) row[6],
              (String) row[7],
              (String) row[8]);
        });
  }

  private List<UUID> storedTags(UUID taskId) {
    return inDb(
        () -> {
          List<?> rows =
              query("SELECT tag_id FROM task_tags WHERE task_id = ?1 ORDER BY tag_id", taskId)
                  .getResultList();
          return rows.stream().map(UUID.class::cast).toList();
        });
  }

  private long taskTagCount() {
    return inDb(
        () -> number("SELECT COUNT(*) FROM task_tags WHERE workspace_id = ?1", workspaceId));
  }

  private List<String> eventTypes(UUID taskId) {
    return inDb(
        () -> {
          List<?> rows =
              query(
                      "SELECT event_type FROM outbox_events "
                          + "WHERE workspace_id = ?1 AND aggregate_id = ?2 ORDER BY seq",
                      workspaceId,
                      taskId)
                  .getResultList();
          return rows.stream().map(String.class::cast).toList();
        });
  }

  private long number(String sql, Object... parameters) {
    return ((Number) query(sql, parameters).getSingleResult()).longValue();
  }

  private Query query(String sql, Object... parameters) {
    Query query = entityManager.createNativeQuery(sql);
    for (int index = 0; index < parameters.length; index++) {
      query.setParameter(index + 1, parameters[index]);
    }
    return query;
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  private <T> T inDb(Supplier<T> action) {
    return inWorkspace(
        () ->
            transactionTemplate.execute(
                tx -> {
                  query(
                          "SELECT set_config('app.current_workspace_id', ?1, true)",
                          workspaceId.toString())
                      .getSingleResult();
                  return action.get();
                }));
  }
}
