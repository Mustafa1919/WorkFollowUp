package com.app.tracker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.dependency.service.TaskDependencyService;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.tag.model.Tag;
import com.app.tracker.tag.service.TagService;
import com.app.tracker.task.dto.TaskActivityResponse;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskActivityService;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Urunlestirme Dalga 1.5 — Activity sekmesi: {@code task_events} okuma yolu + tags_changed/
 * dependency_changed'in artik tarihceye de yazilmasi (V17/V19'un "okuyucusu yok" gerekcesinin
 * kapanmasi). TagIntegrationTest/TaskSubtaskAndDependencyIntegrationTest ile AYNI desen.
 */
@SpringBootTest
class TaskActivityIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TagService tagService;
  @Autowired private TaskDependencyService taskDependencyService;
  @Autowired private TaskActivityService taskActivityService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;

  private UUID workspaceId;
  private Project project;
  private UUID adminUserId;
  private UUID developerUserId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Activity WS"));
    project = inWorkspace(() -> projectService.createProject("ACT", "Activity Project"));
    String adminEmail = "act-admin-" + UUID.randomUUID() + "@tracker.local";
    adminUserId = authService.register(adminEmail, PASSWORD, "Admin").getId();
    membershipService.addMember(workspaceId, adminUserId, WorkspaceRole.ADMIN);
    String devEmail = "act-dev-" + UUID.randomUUID() + "@tracker.local";
    developerUserId = authService.register(devEmail, PASSWORD, "Gelistirici").getId();
    membershipService.addMember(workspaceId, developerUserId, WorkspaceRole.DEVELOPER);
  }

  @Test
  void statusAndAssigneeChangesAppearNewestFirstWithResolvedActorName() {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T", null, adminUserId));
    inWorkspace(() -> taskService.updateStatus(task.getId(), TaskStatus.IN_PROGRESS, adminUserId));
    inWorkspace(() -> taskService.assign(task.getId(), developerUserId, adminUserId));

    List<TaskActivityResponse> page = listAll(task.getId());
    // En yeni once: assignee_changed, status_changed (createTask kendisi tarihceye yazilmaz).
    assertEquals(2, page.size());
    assertEquals("assignee_changed", page.get(0).eventType());
    assertEquals("assigneeId", page.get(0).field());
    assertEquals(developerUserId.toString(), page.get(0).newValue());
    assertEquals("status_changed", page.get(1).eventType());
    assertEquals(TaskStatus.TO_DO, page.get(1).oldValue());
    assertEquals(TaskStatus.IN_PROGRESS, page.get(1).newValue());
    for (TaskActivityResponse entry : page) {
      assertEquals(adminUserId, entry.actorId());
      assertEquals("Admin", entry.actorName());
    }
  }

  @Test
  void tagAssignAndUnassignAreRecordedWithTagName() {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T", null, adminUserId));
    Tag tag = inWorkspace(() -> tagService.createTag("Bug", "#FF0000"));

    inWorkspace(() -> tagService.assign(task.getId(), tag.getId(), adminUserId));
    inWorkspace(() -> tagService.unassign(task.getId(), tag.getId(), adminUserId));

    List<TaskActivityResponse> page = listAll(task.getId());
    assertEquals(2, page.size());
    // En yeni once: kaldirma.
    assertEquals("tags_changed", page.get(0).eventType());
    assertEquals("tag", page.get(0).field());
    assertEquals("Bug", page.get(0).oldValue());
    assertEquals(null, page.get(0).newValue());
    assertEquals("tags_changed", page.get(1).eventType());
    assertEquals(null, page.get(1).oldValue());
    assertEquals("Bug", page.get(1).newValue());
  }

  @Test
  void dependencyLinkAppearsOnBothBlockedAndBlockingTasksFromTheirOwnPerspective() {
    Task blocked = inWorkspace(() -> taskService.createTask(project.getId(), "Blocked"));
    Task blocking = inWorkspace(() -> taskService.createTask(project.getId(), "Blocking"));

    inWorkspace(() -> taskDependencyService.link(blocked.getId(), blocking.getId(), adminUserId));

    List<TaskActivityResponse> blockedActivity = listAll(blocked.getId());
    assertEquals(1, blockedActivity.size());
    assertEquals("dependency_changed", blockedActivity.get(0).eventType());
    assertEquals("blockedBy", blockedActivity.get(0).field());
    assertEquals(blocking.getId().toString(), blockedActivity.get(0).newValue());

    List<TaskActivityResponse> blockingActivity = listAll(blocking.getId());
    assertEquals(1, blockingActivity.size());
    assertEquals("dependency_changed", blockingActivity.get(0).eventType());
    assertEquals("blocks", blockingActivity.get(0).field());
    assertEquals(blocked.getId().toString(), blockingActivity.get(0).newValue());
  }

  @Test
  void keysetPaginationCoversAllRowsWithoutDuplicatesOrGaps() {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T", null, adminUserId));
    inWorkspace(() -> taskService.updateStatus(task.getId(), TaskStatus.IN_PROGRESS, adminUserId));
    inWorkspace(() -> taskService.updateStatus(task.getId(), TaskStatus.REVIEW, adminUserId));
    inWorkspace(() -> taskService.updateStatus(task.getId(), TaskStatus.DONE, adminUserId));

    Set<UUID> seen = new LinkedHashSet<>();
    String cursor = null;
    int pages = 0;
    while (true) {
      String cursorForThisPage = cursor;
      PageResponse<TaskActivityResponse> page =
          inWorkspace(() -> taskActivityService.listActivity(task.getId(), 2, cursorForThisPage));
      pages++;
      page.data().forEach(e -> assertTrue(seen.add(e.id()), "cursor tekrar satir dondurmemeli"));
      if (!page.hasMore()) {
        break;
      }
      cursor = page.nextCursor();
    }
    assertEquals(3, seen.size());
    assertTrue(pages >= 2, "3 satir, limit=2 ile en az 2 sayfa gerekir");
  }

  @Test
  void activityOfDeletedTaskIsNotFound() {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T", null, adminUserId));
    inWorkspace(() -> taskService.deleteTask(task.getId(), adminUserId));

    assertThrows(
        ResourceNotFoundException.class,
        () -> inWorkspace(() -> taskActivityService.listActivity(task.getId(), 20, null)));
  }

  private List<TaskActivityResponse> listAll(UUID taskId) {
    return inWorkspace(() -> taskActivityService.listActivity(taskId, 50, null)).data();
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  private void inWorkspace(Runnable action) {
    tenantExecutor.runAs(workspaceId, action);
  }
}
