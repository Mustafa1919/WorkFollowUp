package com.app.tracker.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.comment.service.CommentService;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.search.dto.CommentSearchResult;
import com.app.tracker.search.dto.SearchResponse;
import com.app.tracker.search.service.SearchService;
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

/**
 * Dalga 1.6 — global arama (V26 {@code search_vector}). {@code app_runtime} ile RLS altinda calisir
 * (AbstractIntegrationTest), bu yuzden generated column + GIN index gercekten Testcontainers'ta
 * dogrulanmis olur (yerel `app_migrator` bypass'inin aksine).
 */
@SpringBootTest
class SearchIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private CommentService commentService;
  @Autowired private SearchService searchService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;

  private UUID workspaceId;
  private Project project;
  private UUID adminUserId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Search WS"));
    project = inWorkspace(() -> projectService.createProject("SRC", "Search Project"));
    String adminEmail = "search-admin-" + UUID.randomUUID() + "@tracker.local";
    adminUserId = authService.register(adminEmail, PASSWORD, "Admin").getId();
    membershipService.addMember(workspaceId, adminUserId, WorkspaceRole.ADMIN);
  }

  @Test
  void findsTaskByTitleAndRanksTitleAboveDescriptionOnlyMatch() {
    Task titleMatch =
        inWorkspace(
            () ->
                taskService.createTask(project.getId(), "Fatura entegrasyonu", null, adminUserId));
    Task descriptionMatch =
        inWorkspace(() -> taskService.createTask(project.getId(), "Baska is", null, adminUserId));
    inWorkspace(
        () ->
            taskService.updateDescription(
                descriptionMatch.getId(), "fatura ile ilgili detay", adminUserId));

    SearchResponse response = inWorkspace(() -> searchService.search("fatura", null));

    assertEquals(2, response.tasks().size());
    assertEquals(
        titleMatch.getId(),
        response.tasks().get(0).id(),
        "baslik eslesmesi agirlik A, ustte olmali");
    assertEquals(descriptionMatch.getId(), response.tasks().get(1).id());
  }

  @Test
  void directProjectKeyAndNumberMatchIsReturnedFirst() {
    Task task =
        inWorkspace(
            () ->
                taskService.createTask(project.getId(), "Herhangi bir baslik", null, adminUserId));
    String key = "SRC-" + task.getTaskNumber();

    SearchResponse response = inWorkspace(() -> searchService.search(key, null));

    assertEquals(1, response.tasks().size());
    assertEquals(task.getId(), response.tasks().get(0).id());
  }

  @Test
  void findsCommentByBodyWithTaskContext() {
    Task task =
        inWorkspace(() -> taskService.createTask(project.getId(), "Gorev", null, adminUserId));
    inWorkspace(
        () -> commentService.addComment(task.getId(), "deploy sirasinda hata aldik", adminUserId));

    SearchResponse response = inWorkspace(() -> searchService.search("deploy", null));

    assertEquals(1, response.comments().size());
    CommentSearchResult hit = response.comments().get(0);
    assertEquals(task.getId(), hit.taskId());
    assertEquals("Gorev", hit.taskTitle());
    assertTrue(hit.snippet().contains("deploy"));
  }

  @Test
  void deletedTaskIsExcludedFromResults() {
    Task task =
        inWorkspace(
            () -> taskService.createTask(project.getId(), "Silinecek gorev", null, adminUserId));
    inWorkspace(() -> taskService.deleteTask(task.getId(), adminUserId));

    SearchResponse response = inWorkspace(() -> searchService.search("Silinecek", null));

    assertTrue(response.tasks().isEmpty());
  }

  @Test
  void blankQueryReturnsEmptyResultsWithoutError() {
    SearchResponse response = inWorkspace(() -> searchService.search("   ", null));

    assertTrue(response.tasks().isEmpty());
    assertTrue(response.comments().isEmpty());
  }

  @Test
  void noMatchReturnsEmptyLists() {
    inWorkspace(
        () -> taskService.createTask(project.getId(), "Alakasiz baslik", null, adminUserId));

    SearchResponse response =
        inWorkspace(() -> searchService.search("hicbirseyeeslesmeyensorgu", null));

    assertTrue(response.tasks().isEmpty());
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  private void inWorkspace(Runnable action) {
    tenantExecutor.runAs(workspaceId, action);
  }
}
