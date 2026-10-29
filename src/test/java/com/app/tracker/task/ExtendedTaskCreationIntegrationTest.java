package com.app.tracker.task;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.outbox.OutboxEventRepository;
import com.app.tracker.core.outbox.OutboxEventRepository.UnprocessedRow;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.service.SprintService;
import com.app.tracker.tag.service.TagService;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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
 * Dilim 05: beklentiler sozlesmeden gelir; gorev mutasyonlari gercek JWT ile HTTP'den gecer.
 * TaskPermissionPolicyIntegrationTest ile ayni context kullanilir. Test transaction'i yoktur; ret
 * sonrasi okumalar bagimsiz transaction'da commit/rollback sonucunu gorur. K7 repository sorgusunu
 * rollback edilen izole fixture ile sinar. Bu ortamda Maven/Docker olmadigindan calistirilmadi;
 * sok-kir plani .dilim/dilim-05-sok-kir-plani.md.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExtendedTaskCreationIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthService authService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private SprintService sprintService;
  @Autowired private TagService tagService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private EntityManager entityManager;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private Clock clock;

  private UUID workspaceId;
  private Project project;
  private Member manager;
  private Member developer;

  private record Member(UUID id, String token) {}

  private record Counts(long tasks, long lastNumber, long outbox) {}

  private record StoredTask(
      UUID projectId,
      UUID workspaceId,
      String title,
      String dueDate,
      String description,
      UUID assigneeId,
      UUID sprintId,
      Integer storyPoint) {}

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Creation WS"));
    manager = member(WorkspaceRole.MANAGER);
    developer = member(WorkspaceRole.DEVELOPER);
    project = inWorkspace(() -> projectService.createProject("EXT", "Extended creation"));
  }

  @ParameterizedTest(name = "K1 legacy request with dueDate={0}")
  @ValueSource(booleans = {false, true})
  void K1_legacyBodyPreservesResponseDatabaseAndCreatedEvent(boolean withDueDate) throws Exception {
    ObjectNode request = titleOnly();
    if (withDueDate) {
      request.put("dueDate", futureDate().toString());
    }
    Counts before = counts();

    JsonNode created = createAccepted(manager, request);

    assertBasicTask(created, request);
    assertEquals(List.of("TASK_CREATED"), eventTypes(id(created)));
    assertEquals(
        new Counts(before.tasks() + 1, before.lastNumber() + 1, before.outbox() + 1), counts());
  }

  @ParameterizedTest(name = "K1 blank description [{0}] adds no event")
  @ValueSource(strings = {"", " \t\n "})
  void K1_blankDescriptionLeavesOptionalFieldsAndEventsEmpty(String description) throws Exception {
    ObjectNode request = titleOnly().put("description", description);

    JsonNode created = createAccepted(manager, request);

    assertBasicTask(created, request);
    assertEquals(List.of("TASK_CREATED"), eventTypes(id(created)));
  }

  @Test
  void K1_explicitNullOptionalFieldsBehaveLikeOmittedFields() throws Exception {
    ObjectNode request = titleOnly();
    for (String field :
        List.of("dueDate", "description", "assigneeId", "storyPoint", "sprintId", "tagIds")) {
      request.putNull(field);
    }

    JsonNode created = createAccepted(manager, request);

    assertBasicTask(created, request);
    assertEquals(List.of("TASK_CREATED"), eventTypes(id(created)));
  }

  @ParameterizedTest(name = "K2 accepts all fields with activeSprint={0}")
  @ValueSource(booleans = {false, true})
  void K2_allFieldsAppearInResponseAndCommittedDatabase(boolean activeSprint) throws Exception {
    ObjectNode request = fullRequest(activeSprint);

    JsonNode created = createAccepted(manager, request);

    assertFullTask(created, request);
  }

  @ParameterizedTest(name = "K2/R4d acceptance boundary storyPoint={0}")
  @ValueSource(ints = {0, 100})
  void K2_R4d_storyPointBoundariesAreAcceptedAndPersisted(int storyPoint) throws Exception {
    ObjectNode request = fullRequest(false).put("storyPoint", storyPoint);

    assertFullTask(createAccepted(manager, request), request);
  }

  @Test
  void K3_extendedCreationMatchesEventsFromSequentialHttpEndpoints() throws Exception {
    ObjectNode request = fullRequest(false);
    JsonNode extended = createAccepted(manager, request);
    assertFullTask(extended, request);

    // Referans da AYNI testte, ikinci gorevde ve yalniz HTTP uclarindan uretilir.
    ObjectNode referenceRequest = titleOnly().put("dueDate", request.path("dueDate").asString());
    UUID referenceId = id(createAccepted(manager, referenceRequest));
    update(referenceId, "description", field(request, "description")).andExpect(status().isOk());
    update(referenceId, "assignee", field(request, "assigneeId")).andExpect(status().isOk());
    update(referenceId, "story-point", field(request, "storyPoint")).andExpect(status().isOk());
    JsonNode reference =
        response(
            update(referenceId, "sprint", field(request, "sprintId")).andExpect(status().isOk()));
    for (JsonNode tagId : request.path("tagIds")) {
      reference =
          response(
              mockMvc
                  .perform(
                      put("/api/v1/tasks/{id}/tags/{tagId}", referenceId, tagId.asString())
                          .header("Authorization", "Bearer " + manager.token())
                          .header("X-Workspace-Id", workspaceId.toString()))
                  .andExpect(status().isOk()));
    }
    assertFullTask(reference, referenceRequestFor(request, referenceRequest));
    List<String> referenceEvents = eventTypes(referenceId);
    assertFalse(referenceEvents.isEmpty());
    assertTrue(referenceEvents.size() > 1, "Referans yalniz olusturma olayindan ibaret olamaz");

    // Her iki sorgu aggregateId ile ayrilir ve seq sirasini kullanir; sabit dizi yok.
    assertEquals(referenceEvents, eventTypes(id(extended)));
  }

  @Test
  void R4a_foreignWorkspaceTagRejectsAndRollsBackEvenEarlierValidTag() throws Exception {
    UUID otherWorkspace = UUID.randomUUID();
    tenantExecutor.runAs(
        null, () -> workspaceService.createWorkspace(otherWorkspace, "Foreign tags"));
    UUID foreignTag =
        tenantExecutor.runAs(
            otherWorkspace, () -> tagService.createTag("Foreign", "#abcdef").getId());
    ObjectNode request = fullRequest(false);
    UUID acceptedId = id(createAccepted(manager, request));
    assertEquals(2, storedTagIds(acceptedId).size());
    // Kabul esinde yanit alanlari yerine commit kontrol edilir: dis transaction sokuldugunde
    // test gec ret asamasina ulasip asil rollback sayaclarini da sinamalidir.
    // Ilk etiket gecerli; son etiket gec reddedilirse onceki tum yazmalar da geri alinmali.
    request.set(
        "tagIds",
        objectMapper.valueToTree(
            List.of(request.path("tagIds").get(0).asString(), foreignTag.toString())));

    assertRejectedWithoutWrites(manager, request, 0);
  }

  @Test
  void R4b_completedSprintRejectsAndRollsBackDescriptionAssignmentAndPoints() throws Exception {
    ObjectNode request = fullRequest(true);
    assertFullTask(createAccepted(manager, request), request);
    UUID sprintId = UUID.fromString(request.path("sprintId").asString());
    inWorkspace(() -> sprintService.completeSprint(sprintId));

    assertRejectedWithoutWrites(manager, request, 400);
  }

  @Test
  void R4c_viewerAssigneeRejectsAndRollsBackCreationAndDescription() throws Exception {
    Member viewer = member(WorkspaceRole.VIEWER);
    ObjectNode request = fullRequest(false);
    assertFullTask(createAccepted(manager, request), request);
    request.put("assigneeId", viewer.id().toString());

    assertRejectedWithoutWrites(manager, request, 400);
  }

  @Test
  void R4c_existingUserWithoutMembershipRejectsAndRollsBackCreationAndDescription()
      throws Exception {
    UUID outsider =
        authService
            .register(
                "outsider-" + UUID.randomUUID() + "@tracker.local",
                PASSWORD,
                "Outsider",
                PrivacyNotice.CURRENT_VERSION)
            .getId();
    ObjectNode request = fullRequest(false);
    assertFullTask(createAccepted(manager, request), request);
    request.put("assigneeId", outsider.toString());

    assertRejectedWithoutWrites(manager, request, 400);
  }

  @Test
  void R4d_storyPoint101Returns422WithoutTaskCounterOrOutboxWrites() throws Exception {
    ObjectNode request = fullRequest(false).put("storyPoint", 100);
    assertFullTask(createAccepted(manager, request), request);
    request.put("storyPoint", 101);

    assertRejectedWithoutWrites(manager, request, 422);
  }

  @ParameterizedTest(name = "R5 developer forbidden for {0} body; manager accepts same body")
  @ValueSource(strings = {"TITLE_ONLY", "ALL_FIELDS"})
  void R5_developerGets403ForEveryValidBodyIncludingMissingOptionals(String shape)
      throws Exception {
    ObjectNode request =
        switch (shape) {
          case "TITLE_ONLY" -> titleOnly();
          case "ALL_FIELDS" -> fullRequest(false);
          default -> throw new IllegalArgumentException(shape);
        };
    JsonNode accepted = createAccepted(manager, request);
    if (shape.equals("ALL_FIELDS")) {
      assertFullTask(accepted, request);
    } else {
      assertBasicTask(accepted, request);
    }

    assertRejectedWithoutWrites(developer, request, 403);
  }

  @Test
  void K6_managerCanCreateWithTitleOnlyAndAllOptionalPropertiesAbsent() throws Exception {
    ObjectNode request = titleOnly();
    assertEquals(1, request.size());

    assertBasicTask(createAccepted(manager, request), request);
  }

  @Test
  void K7_relayBatchPreservesSingleTransactionWriteOrderRegardlessOfCreatedAt() {
    UUID aggregateId = UUID.randomUUID();
    List<String> writtenTypes =
        List.of("TASK_CREATED", "TASK_ASSIGNED", "TASK_STORY_POINT_UPDATED");

    transactionTemplate.executeWithoutResult(
        tx -> {
          // Fixture hic commit edilmez: scheduled relay goremez, assertion hatasinda da silinir.
          tx.setRollbackOnly();
          for (String eventType : writtenTypes) {
            outboxEventRepository.write("task.events", eventType, aggregateId, workspaceId, "{}");
          }
          List<UnprocessedRow> sameTimestamp = unprocessedRows(aggregateId);
          assertEquals(
              writtenTypes, sameTimestamp.stream().map(UnprocessedRow::eventType).toList());
          assertEquals(
              1L,
              sameTimestamp.stream().map(UnprocessedRow::createdAt).distinct().count(),
              "Tek transaction'daki NOW() damgalari esittir");

          // Esit damgalarda eski ORDER BY created_at tesadufen dogru sirayi verebilir.
          // seq degismeden damgalari tersle: o sorguya donus kesin assertion hatasi olmali.
          for (int index = 0; index < writtenTypes.size(); index++) {
            assertEquals(
                1,
                query(
                        "UPDATE outbox_events SET created_at = "
                            + "NOW() + CAST(?1 AS integer) * INTERVAL '1 second' "
                            + "WHERE workspace_id = ?2 AND aggregate_id = ?3 AND event_type = ?4",
                        writtenTypes.size() - index,
                        workspaceId,
                        aggregateId,
                        writtenTypes.get(index))
                    .executeUpdate());
          }
          assertEquals(
              writtenTypes,
              unprocessedRows(aggregateId).stream().map(UnprocessedRow::eventType).toList(),
              "Relay yazim sirasini created_at yerine seq ile korumali");
        });

    long remaining =
        inDb(
            () ->
                ((Number)
                        query(
                                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?1",
                                aggregateId)
                            .getSingleResult())
                    .longValue());
    assertEquals(0L, remaining, "K7 fixture'i paylasilan outbox'ta satir birakmamali");
  }

  private List<UnprocessedRow> unprocessedRows(UUID aggregateId) {
    // Gercek relay sorgusu globaldir. Diger test satirlarini degistirmeden kendi aggregate'imizi
    // ayir; LIMIT'in eski satirlar nedeniyle fixture'i dislamamasi icin tum test kuyrugunu oku.
    // FOR UPDATE kilitleri yalniz kisa rollback transaction'i boyunca tutulur.
    return outboxEventRepository.findUnprocessedBatch(Integer.MAX_VALUE).stream()
        .filter(
            row -> aggregateId.equals(row.aggregateId()) && workspaceId.equals(row.workspaceId()))
        .toList();
  }

  private Member member(String role) {
    String email = "extended-" + UUID.randomUUID() + "@tracker.local";
    UUID userId =
        authService
            .register(email, PASSWORD, "Creation " + role, PrivacyNotice.CURRENT_VERSION)
            .getId();
    membershipService.addMember(workspaceId, userId, role);
    return new Member(userId, authService.login(email, PASSWORD, "127.0.0.1").accessToken());
  }

  private LocalDate futureDate() {
    return LocalDate.now(clock).plusDays(7);
  }

  private ObjectNode titleOnly() {
    return objectMapper.createObjectNode().put("title", "Extended " + UUID.randomUUID());
  }

  private ObjectNode fullRequest(boolean activeSprint) {
    Sprint sprint =
        inWorkspace(
            () ->
                sprintService.createSprint(
                    project.getId(),
                    "Creation sprint",
                    "Goal",
                    futureDate(),
                    futureDate().plusDays(14)));
    if (activeSprint) {
      inWorkspace(() -> sprintService.startSprint(sprint.getId()));
    }
    // Ters alfabetik sira, istek sirasini etiket listeleme sirasindan ayirir.
    UUID firstTag = inWorkspace(() -> tagService.createTag("Zeta", "#123456").getId());
    UUID secondTag = inWorkspace(() -> tagService.createTag("Alpha", "#abcdef").getId());
    ObjectNode request =
        titleOnly()
            .put("dueDate", futureDate().toString())
            .put("description", "## Creation\nAll fields survive the transaction.")
            .put("assigneeId", developer.id().toString())
            .put("storyPoint", 5)
            .put("sprintId", sprint.getId().toString());
    request.set("tagIds", objectMapper.valueToTree(List.of(firstTag, secondTag)));
    return request;
  }

  private ObjectNode field(ObjectNode request, String name) {
    ObjectNode body = objectMapper.createObjectNode();
    body.set(name, request.path(name));
    return body;
  }

  private ObjectNode referenceRequestFor(ObjectNode full, ObjectNode reference) {
    ObjectNode expected = full.deepCopy();
    expected.set("title", reference.path("title"));
    return expected;
  }

  private ResultActions create(Member actor, ObjectNode request) throws Exception {
    return mockMvc.perform(
        post("/api/v1/projects/{id}/tasks", project.getId())
            .header("Authorization", "Bearer " + actor.token())
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }

  private JsonNode createAccepted(Member actor, ObjectNode request) throws Exception {
    return response(create(actor, request).andExpect(status().isCreated()));
  }

  private ResultActions update(UUID taskId, String endpoint, ObjectNode request) throws Exception {
    return mockMvc.perform(
        put("/api/v1/tasks/{id}/" + endpoint, taskId)
            .header("Authorization", "Bearer " + manager.token())
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }

  private JsonNode response(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private UUID id(JsonNode response) {
    return UUID.fromString(response.path("id").asString());
  }

  private void assertBasicTask(JsonNode created, ObjectNode request) throws Exception {
    UUID taskId = id(created);
    String dueDate =
        request.path("dueDate").isTextual() ? request.path("dueDate").asString() : null;
    assertEquals(request.path("title"), created.path("title"));
    assertEquals(project.getId().toString(), created.path("projectId").asString());
    assertEquals("To Do", created.path("status").asString());
    assertTrue(created.path("taskNumber").asInt() > 0);
    assertTrue(created.hasNonNull("createdAt"));
    if (dueDate == null) {
      assertTrue(created.path("dueDate").isNull());
    } else {
      assertEquals(dueDate, created.path("dueDate").asString());
    }
    for (String field : List.of("assigneeId", "storyPoint", "sprintId")) {
      assertTrue(created.path(field).isNull(), field);
    }
    assertTrue(created.path("tags").isArray());
    assertEquals(0, created.path("tags").size());
    assertEquals(
        new StoredTask(
            project.getId(),
            workspaceId,
            request.path("title").asString(),
            dueDate,
            null,
            null,
            null,
            null),
        storedTask(taskId));
    assertEquals(List.of(), storedTagIds(taskId));
    assertTrue(taskDetail(taskId).path("description").isNull());
  }

  private void assertFullTask(JsonNode created, ObjectNode request) throws Exception {
    UUID taskId = id(created);
    assertEquals(project.getId().toString(), created.path("projectId").asString());
    for (String field : List.of("title", "dueDate", "assigneeId", "storyPoint", "sprintId")) {
      assertEquals(request.path(field), created.path(field), field);
    }
    assertTrue(created.path("tags").isArray());
    assertEquals(2, created.path("tags").size());
    Set<UUID> expectedTags =
        Set.of(
            UUID.fromString(request.path("tagIds").get(0).asString()),
            UUID.fromString(request.path("tagIds").get(1).asString()));
    Set<UUID> responseTags =
        Set.of(
            UUID.fromString(created.path("tags").get(0).path("id").asString()),
            UUID.fromString(created.path("tags").get(1).path("id").asString()));
    assertEquals(expectedTags, responseTags);
    List<UUID> persistedTags = storedTagIds(taskId);
    assertEquals(2, persistedTags.size());
    assertEquals(expectedTags, Set.copyOf(persistedTags));
    assertEquals(
        new StoredTask(
            project.getId(),
            workspaceId,
            request.path("title").asString(),
            request.path("dueDate").asString(),
            request.path("description").asString(),
            UUID.fromString(request.path("assigneeId").asString()),
            UUID.fromString(request.path("sprintId").asString()),
            request.path("storyPoint").asInt()),
        storedTask(taskId));
    assertEquals(request.path("description"), taskDetail(taskId).path("description"));
  }

  private JsonNode taskDetail(UUID taskId) throws Exception {
    return response(
        mockMvc
            .perform(
                get("/api/v1/tasks/{id}/detail", taskId)
                    .header("Authorization", "Bearer " + manager.token())
                    .header("X-Workspace-Id", workspaceId.toString()))
            .andExpect(status().isOk()));
  }

  private void assertRejectedWithoutWrites(Member actor, ObjectNode request, int expectedStatus)
      throws Exception {
    Counts before = counts();
    int actualStatus = create(actor, request).andReturn().getResponse().getStatus();
    Counts after = counts();
    // Yanlis HTTP kodu da gelse uc rollback olcumu birlikte raporlanir.
    assertAll(
        () -> {
          if (expectedStatus == 0) {
            assertTrue(
                actualStatus >= 400 && actualStatus < 500, "R4a HTTP status: " + actualStatus);
          } else {
            assertEquals(expectedStatus, actualStatus, "Sozlesmedeki HTTP status");
          }
        },
        () -> assertEquals(before.tasks(), after.tasks(), "Projedeki tum gorevlerin sayisi"),
        () -> assertEquals(before.lastNumber(), after.lastNumber(), "task_counters.last_number"),
        () -> assertEquals(before.outbox(), after.outbox(), "Workspace'teki tum outbox satirlari"));
  }

  private Counts counts() {
    return inDb(
        () ->
            new Counts(
                ((Number)
                        query("SELECT COUNT(*) FROM tasks WHERE project_id = ?1", project.getId())
                            .getSingleResult())
                    .longValue(),
                ((Number)
                        query(
                                "SELECT last_number FROM task_counters WHERE project_id = ?1",
                                project.getId())
                            .getSingleResult())
                    .longValue(),
                ((Number)
                        query(
                                "SELECT COUNT(*) FROM outbox_events WHERE workspace_id = ?1",
                                workspaceId)
                            .getSingleResult())
                    .longValue()));
  }

  private StoredTask storedTask(UUID taskId) {
    return inDb(
        () -> {
          Object[] row =
              (Object[])
                  query(
                          "SELECT project_id, workspace_id, title, due_date::text, description, "
                              + "assignee_id, sprint_id, custom_fields ->> 'story_point' "
                              + "FROM tasks WHERE id = ?1",
                          taskId)
                      .getSingleResult();
          return new StoredTask(
              (UUID) row[0],
              (UUID) row[1],
              (String) row[2],
              (String) row[3],
              (String) row[4],
              (UUID) row[5],
              (UUID) row[6],
              row[7] == null ? null : Integer.valueOf((String) row[7]));
        });
  }

  private List<UUID> storedTagIds(UUID taskId) {
    return inDb(
        () -> {
          List<?> rows =
              query("SELECT tag_id FROM task_tags WHERE task_id = ?1 ORDER BY tag_id", taskId)
                  .getResultList();
          return rows.stream().map(UUID.class::cast).toList();
        });
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
    // TransactionTemplate servis AOP'sinden gecmez; RLS GUC'u bu transaction'a baglanir.
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
