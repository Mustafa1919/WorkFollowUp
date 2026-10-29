package com.app.tracker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.sprint.service.SprintService;
import com.app.tracker.tag.model.Tag;
import com.app.tracker.tag.service.TagService;
import com.app.tracker.task.dto.BulkOperation;
import com.app.tracker.task.dto.BulkTaskRequest;
import com.app.tracker.task.dto.UpdateAssigneeRequest;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dilim 02 sozlesmesi: gercek JWT, workspace filtresi ve method-security ile HTTP izinleri. Fikstur
 * yazmalari servis transaction'larinda yapilir; sinanan mutasyonlar yalniz MockMvc'den gecer.
 * Sinif/test transaction'i yoktur: her DB dogrulamasi yeni servis transaction'inda okur.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskPermissionPolicyIntegrationTest extends AbstractIntegrationTest {

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

  private UUID workspaceId;
  private Project project;
  private Member manager;
  private Member developer;
  private Member otherDeveloper;
  private Member viewer;

  private record Member(UUID id, String token) {}

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Policy WS"));
    manager = member(WorkspaceRole.MANAGER);
    developer = member(WorkspaceRole.DEVELOPER);
    otherDeveloper = member(WorkspaceRole.DEVELOPER);
    viewer = member(WorkspaceRole.VIEWER);
    project = inWorkspace(() -> projectService.createProject("POL", "Permission Policy"));
  }

  @Test
  void K2_developerCanAssignUnassignedTaskToSelf() throws Exception {
    Task task = taskAssignedTo(null);

    assignAs(developer, task.getId(), developer.id()).andExpect(status().isOk());

    assertAssignee(task.getId(), developer.id());
  }

  @Test
  void R2_developerCannotAssignUnassignedTaskToAnotherMember() throws Exception {
    Task task = taskAssignedTo(null);
    JsonNode before = persistedTask(task.getId());

    assignAs(developer, task.getId(), otherDeveloper.id()).andExpect(status().isForbidden());

    assertEquals(before, persistedTask(task.getId()));
    assertAssignee(task.getId(), null);
  }

  @Test
  void K3_developerCanReleaseOwnAssignment() throws Exception {
    Task task = taskAssignedTo(developer.id());

    assignAs(developer, task.getId(), null).andExpect(status().isOk());

    assertAssignee(task.getId(), null);
  }

  @Test
  void K2_developerCanRepeatOwnAssignmentAsNoOp() throws Exception {
    Task task = taskAssignedTo(developer.id());
    JsonNode before = persistedTask(task.getId());

    assignAs(developer, task.getId(), developer.id()).andExpect(status().isOk());

    assertEquals(before, persistedTask(task.getId()));
    assertAssignee(task.getId(), developer.id());
  }

  @Test
  void R3a_developerCannotTakeTaskAssignedToAnotherMember() throws Exception {
    Task task = taskAssignedTo(otherDeveloper.id());
    JsonNode before = persistedTask(task.getId());

    assignAs(developer, task.getId(), developer.id()).andExpect(status().isForbidden());

    assertEquals(before, persistedTask(task.getId()));
    assertAssignee(task.getId(), otherDeveloper.id());
  }

  @Test
  void R3b_developerCannotReleaseAnotherMembersAssignment() throws Exception {
    Task task = taskAssignedTo(otherDeveloper.id());
    JsonNode before = persistedTask(task.getId());

    assignAs(developer, task.getId(), null).andExpect(status().isForbidden());

    assertEquals(before, persistedTask(task.getId()));
    assertAssignee(task.getId(), otherDeveloper.id());
  }

  @Test
  void R3c_developerCannotTransferOwnTaskToAnotherMember() throws Exception {
    Task task = taskAssignedTo(developer.id());
    JsonNode before = persistedTask(task.getId());

    assignAs(developer, task.getId(), otherDeveloper.id()).andExpect(status().isForbidden());

    assertEquals(before, persistedTask(task.getId()));
    assertAssignee(task.getId(), developer.id());
  }

  @ParameterizedTest(name = "R2/R3b policy runs before no-op: {0} -> {0}")
  @CsvSource({"NONE", "OTHER"})
  void R2_R3b_onlyOwnAssignmentMayBeRepeatedAsNoOp(String initial) throws Exception {
    // Arayuz yalniz KENDINE atanmis goreve kendini yeniden atamayi serbest birakir.
    // Bu retler, checkAssign cagrisinin no-op erken donusunden sonraya tasinmasini yakalar.
    UUID assigneeId = assignee(initial);
    Task task = taskAssignedTo(assigneeId);
    JsonNode before = persistedTask(task.getId());

    assignAs(developer, task.getId(), assigneeId).andExpect(status().isForbidden());

    assertEquals(before, persistedTask(task.getId()));
    assertAssignee(task.getId(), assigneeId);
  }

  @ParameterizedTest(name = "K4 accepts {0}: {1} -> {2}")
  @CsvSource({
    "R2, NONE, OTHER",
    "R3a, OTHER, SELF",
    "R3b, OTHER, NONE",
    "R3c, SELF, OTHER",
    "unassigned no-op, NONE, NONE",
    "other member no-op, OTHER, OTHER"
  })
  void K4_managerCanPerformEveryAssignmentRejectedForDeveloper(
      String rejection, String initial, String target) throws Exception {
    Task task = taskAssignedTo(assignee(initial));
    UUID targetId = assignee(target);

    assignAs(manager, task.getId(), targetId).andExpect(status().isOk());

    assertEquals(
        targetId,
        inWorkspace(() -> taskService.getDetail(task.getId())).task().getAssigneeId(),
        "K4 acceptance counterpart for " + rejection);
  }

  @ParameterizedTest(name = "K5 developer bulk {0}")
  @EnumSource(
      value = BulkOperation.class,
      names = {"STATUS", "ADD_TAG", "REMOVE_TAG"})
  void K5_developerCanBulkEditEveryListedTask(BulkOperation operation) throws Exception {
    List<UUID> taskIds = List.of(taskAssignedTo(null).getId(), taskAssignedTo(null).getId());
    Tag tag = inWorkspace(() -> tagService.createTag("Policy tag", "#123456"));
    for (UUID taskId : taskIds) {
      assertNotEquals(
          TaskStatus.IN_PROGRESS,
          inWorkspace(() -> taskService.getDetail(taskId)).task().getStatus());
    }
    if (operation == BulkOperation.REMOVE_TAG) {
      for (UUID taskId : taskIds) {
        inWorkspace(() -> tagService.assign(taskId, tag.getId(), manager.id()));
        assertEquals(
            List.of(tag.getId()),
            inWorkspace(() -> tagService.tagsForTask(taskId)).stream().map(t -> t.id()).toList());
      }
    } else {
      for (UUID taskId : taskIds) {
        assertEquals(List.of(), inWorkspace(() -> tagService.tagsForTask(taskId)));
      }
    }

    BulkTaskRequest request =
        new BulkTaskRequest(
            taskIds,
            operation,
            operation == BulkOperation.STATUS ? TaskStatus.IN_PROGRESS : null,
            null,
            null,
            operation == BulkOperation.STATUS ? null : tag.getId());
    bulkAs(developer, request).andExpect(status().isOk());

    for (UUID taskId : taskIds) {
      if (operation == BulkOperation.STATUS) {
        assertEquals(
            TaskStatus.IN_PROGRESS,
            inWorkspace(() -> taskService.getDetail(taskId)).task().getStatus());
      } else {
        List<UUID> expected = operation == BulkOperation.ADD_TAG ? List.of(tag.getId()) : List.of();
        assertEquals(
            expected,
            inWorkspace(() -> tagService.tagsForTask(taskId)).stream().map(t -> t.id()).toList());
      }
    }
  }

  @ParameterizedTest(name = "R5 developer bulk {0} leaves both DB rows unchanged")
  @EnumSource(
      value = BulkOperation.class,
      names = {"SPRINT", "ASSIGNEE"})
  void R5_developerCannotBulkPlanAndNoListedTaskChanges(BulkOperation operation) throws Exception {
    // Iki gorev de gecerli ve atanmamis: ASSIGNEE korumasi kaldirilirsa ince kural
    // kendini atamayi ENGELLEMEZ. Boylece bulk izni eksikligi baska bir 403 ile maskelenmez.
    List<UUID> taskIds = List.of(taskAssignedTo(null).getId(), taskAssignedTo(null).getId());
    BulkTaskRequest request = planningRequest(operation, taskIds);
    List<JsonNode> before = taskIds.stream().map(this::persistedTask).toList();

    bulkAs(developer, request).andExpect(status().isForbidden());

    assertEquals(before, taskIds.stream().map(this::persistedTask).toList());
    for (UUID taskId : taskIds) {
      Task task = inWorkspace(() -> taskService.getDetail(taskId)).task();
      assertNull(task.getAssigneeId());
      assertNull(task.getSprintId());
    }
  }

  @ParameterizedTest(name = "K6 manager bulk {0} changes both DB rows")
  @EnumSource(
      value = BulkOperation.class,
      names = {"SPRINT", "ASSIGNEE"})
  void K6_managerCanBulkPlanEveryListedTask(BulkOperation operation) throws Exception {
    List<UUID> taskIds = List.of(taskAssignedTo(null).getId(), taskAssignedTo(null).getId());
    // R5 ile ayni gecerli govde ve baslangic durumu; yalniz HTTP aktoru degisir.
    BulkTaskRequest request = planningRequest(operation, taskIds);
    List<JsonNode> before = taskIds.stream().map(this::persistedTask).toList();

    bulkAs(manager, request).andExpect(status().isOk());

    for (int i = 0; i < taskIds.size(); i++) {
      UUID taskId = taskIds.get(i);
      Task task = inWorkspace(() -> taskService.getDetail(taskId)).task();
      assertNotEquals(before.get(i), persistedTask(taskId));
      if (operation == BulkOperation.SPRINT) {
        assertEquals(request.sprintId(), task.getSprintId());
        assertNull(task.getAssigneeId());
      } else {
        assertEquals(developer.id(), task.getAssigneeId());
        assertNull(task.getSprintId());
      }
    }
  }

  @Test
  void R8_viewerCannotAssignUnassignedTaskToSelf() throws Exception {
    Task task = taskAssignedTo(null);
    JsonNode before = persistedTask(task.getId());

    assignAs(viewer, task.getId(), viewer.id()).andExpect(status().isForbidden());

    assertEquals(before, persistedTask(task.getId()));
    assertAssignee(task.getId(), null);
  }

  private Member member(String role) {
    String email = "policy-" + UUID.randomUUID() + "@tracker.local";
    UUID userId =
        authService
            .register(email, PASSWORD, "Policy " + role, PrivacyNotice.CURRENT_VERSION)
            .getId();
    membershipService.addMember(workspaceId, userId, role);
    return new Member(userId, authService.login(email, PASSWORD, "127.0.0.1").accessToken());
  }

  private Task taskAssignedTo(UUID assigneeId) {
    Task task =
        inWorkspace(
            () -> taskService.createTask(project.getId(), "Policy task", null, manager.id()));
    if (assigneeId != null) {
      inWorkspace(() -> taskService.assign(task.getId(), assigneeId, manager.id()));
    }
    assertAssignee(task.getId(), assigneeId);
    return task;
  }

  private UUID assignee(String label) {
    return switch (label) {
      case "NONE" -> null;
      case "SELF" -> developer.id();
      case "OTHER" -> otherDeveloper.id();
      default -> throw new IllegalArgumentException(label);
    };
  }

  private BulkTaskRequest planningRequest(BulkOperation operation, List<UUID> taskIds) {
    UUID sprintId = null;
    if (operation == BulkOperation.SPRINT) {
      LocalDate start = LocalDate.now().plusYears(1);
      sprintId =
          inWorkspace(
                  () ->
                      sprintService.createSprint(
                          project.getId(),
                          "Policy sprint",
                          "Permission test",
                          start,
                          start.plusDays(14)))
              .getId();
    }
    return new BulkTaskRequest(
        taskIds,
        operation,
        null,
        sprintId,
        operation == BulkOperation.ASSIGNEE ? developer.id() : null,
        null);
  }

  private ResultActions assignAs(Member actor, UUID taskId, UUID assigneeId) throws Exception {
    return mockMvc.perform(
        put("/api/v1/tasks/{id}/assignee", taskId)
            .header("Authorization", "Bearer " + actor.token())
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new UpdateAssigneeRequest(assigneeId))));
  }

  private ResultActions bulkAs(Member actor, BulkTaskRequest request) throws Exception {
    return mockMvc.perform(
        post("/api/v1/tasks/bulk")
            .header("Authorization", "Bearer " + actor.token())
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
  }

  private void assertAssignee(UUID taskId, UUID expected) {
    assertEquals(expected, inWorkspace(() -> taskService.getDetail(taskId)).task().getAssigneeId());
  }

  private JsonNode persistedTask(UUID taskId) {
    // HTTP yanitini veya fikstur nesnesini degil, DB'den yeniden okunan gorevin BUTUN
    // maplenmis alanlarini kopyalar. getDetail transaction'i RLS baglamini servis AOP'siyle kurar.
    return objectMapper.valueToTree(inWorkspace(() -> taskService.getDetail(taskId)).task());
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }
}
