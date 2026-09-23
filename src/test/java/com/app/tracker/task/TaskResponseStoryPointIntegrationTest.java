package com.app.tracker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Story point (tasks.custom_fields->>'story_point', TaskCustomFieldRepository) artik
 * TaskResponse.storyPoint alaninda donuyor — tek-gorev uc noktalarinda dogrudan, liste uc
 * noktalarinda ({@code GET /projects/{id}/tasks}) TaskCustomFieldRepository#getStoryPoints ile
 * batch (N+1 onlemek icin, TagService#tagsForTasks ile AYNI desen).
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskResponseStoryPointIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  private UUID workspaceId;
  private Project project;
  private String token;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "SP WS"));
    project = inWorkspace(() -> projectService.createProject("SP", "Story Point Project"));
    String email = "sp-owner-" + UUID.randomUUID() + "@tracker.local";
    UUID userId = authService.register(email, PASSWORD, "Owner").getId();
    membershipService.addMember(workspaceId, userId, WorkspaceRole.ADMIN);
    token = authService.login(email, PASSWORD, "127.0.0.1").accessToken();
  }

  @Test
  void updatingStoryPointIsReflectedInSingleTaskResponse() throws Exception {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T"));

    String body =
        mockMvc
            .perform(
                put("/api/v1/tasks/" + task.getId() + "/story-point")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Workspace-Id", workspaceId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"storyPoint\":5}"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertEquals(5, objectMapper.readTree(body).get("storyPoint").asInt());
  }

  @Test
  void listEndpointBatchLoadsStoryPointsWithoutMissingOrMixingTasks() throws Exception {
    Task withPoints = inWorkspace(() -> taskService.createTask(project.getId(), "with-points"));
    Task withoutPoints =
        inWorkspace(() -> taskService.createTask(project.getId(), "without-points"));
    mockMvc.perform(
        put("/api/v1/tasks/" + withPoints.getId() + "/story-point")
            .header("Authorization", "Bearer " + token)
            .header("X-Workspace-Id", workspaceId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"storyPoint\":8}"));

    String body =
        mockMvc
            .perform(
                get("/api/v1/projects/" + project.getId() + "/tasks")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Workspace-Id", workspaceId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode data = objectMapper.readTree(body).get("data");
    JsonNode withPointsJson = findById(data, withPoints.getId());
    JsonNode withoutPointsJson = findById(data, withoutPoints.getId());
    assertEquals(8, withPointsJson.get("storyPoint").asInt());
    assertTrue(withoutPointsJson.get("storyPoint").isNull());
  }

  private static JsonNode findById(JsonNode array, UUID id) {
    for (JsonNode node : array) {
      if (id.toString().equals(node.get("id").asText())) {
        return node;
      }
    }
    throw new AssertionError("Gorev bulunamadi: " + id);
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }
}
