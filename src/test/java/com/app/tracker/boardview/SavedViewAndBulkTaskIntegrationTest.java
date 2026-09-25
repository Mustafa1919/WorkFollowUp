package com.app.tracker.boardview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.boardview.model.SavedView;
import com.app.tracker.boardview.service.SavedViewService;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.tag.model.Tag;
import com.app.tracker.tag.service.TagService;
import com.app.tracker.task.dto.BulkOperation;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.BulkTaskService;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

/**
 * Dalga 1.7 (V27) — kayitli gorunumler (kisisel, sahiplik servis katmaninda) + toplu islem (tek
 * transaction, TaskService/TagService uzerinden). TaskSubtaskAndDependencyIntegrationTest ile AYNI
 * desen (servis katmani + tenantExecutor.runAs).
 */
@SpringBootTest
class SavedViewAndBulkTaskIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TagService tagService;
  @Autowired private BulkTaskService bulkTaskService;
  @Autowired private SavedViewService savedViewService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;

  private UUID workspaceId;
  private Project project;
  private UUID adminUserId;
  private UUID otherUserId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Bulk WS"));
    project = inWorkspace(() -> projectService.createProject("BLK", "Bulk Project"));
    adminUserId = registerMember("bulk-owner", WorkspaceRole.ADMIN);
    otherUserId = registerMember("bulk-other", WorkspaceRole.DEVELOPER);
  }

  // ---- Saved views ----

  @Test
  void savedViewsAreScopedToOwner() {
    inWorkspace(
        () -> savedViewService.create(project.getId(), adminUserId, "Benim Panom", "{\"a\":1}"));
    inWorkspace(
        () -> savedViewService.create(project.getId(), otherUserId, "Digerinin Panosu", "{}"));

    List<SavedView> adminViews =
        inWorkspace(() -> savedViewService.list(project.getId(), adminUserId));
    assertEquals(1, adminViews.size());
    assertEquals("Benim Panom", adminViews.get(0).getName());

    List<SavedView> otherViews =
        inWorkspace(() -> savedViewService.list(project.getId(), otherUserId));
    assertEquals(1, otherViews.size());
    assertEquals("Digerinin Panosu", otherViews.get(0).getName());
  }

  @Test
  void onlyOwnerCanDeleteSavedView() {
    SavedView view =
        inWorkspace(() -> savedViewService.create(project.getId(), adminUserId, "V", "{}"));

    assertThrows(
        AccessDeniedException.class,
        () ->
            inWorkspace(
                () -> {
                  savedViewService.delete(view.getId(), otherUserId);
                  return null;
                }));

    inWorkspace(
        () -> {
          savedViewService.delete(view.getId(), adminUserId);
          return null;
        });
    assertTrue(inWorkspace(() -> savedViewService.list(project.getId(), adminUserId)).isEmpty());
  }

  @Test
  void deletingUnknownViewIsNotFound() {
    assertThrows(
        ResourceNotFoundException.class,
        () ->
            inWorkspace(
                () -> {
                  savedViewService.delete(UUID.randomUUID(), adminUserId);
                  return null;
                }));
  }

  // ---- Bulk ----

  @Test
  void bulkStatusUpdatesAllTasksInOneTransaction() {
    Task t1 = inWorkspace(() -> taskService.createTask(project.getId(), "T1"));
    Task t2 = inWorkspace(() -> taskService.createTask(project.getId(), "T2"));

    List<Task> updated =
        inWorkspace(
            () ->
                bulkTaskService.apply(
                    List.of(t1.getId(), t2.getId()),
                    BulkOperation.STATUS,
                    TaskStatus.IN_PROGRESS,
                    null,
                    null,
                    null,
                    adminUserId));

    assertEquals(2, updated.size());
    assertTrue(updated.stream().allMatch(t -> TaskStatus.IN_PROGRESS.equals(t.getStatus())));
  }

  @Test
  void bulkAddTagAppliesToEveryTask() {
    Task t1 = inWorkspace(() -> taskService.createTask(project.getId(), "T1"));
    Task t2 = inWorkspace(() -> taskService.createTask(project.getId(), "T2"));
    Tag tag = inWorkspace(() -> tagService.createTag("Bug", "#FF0000"));

    inWorkspace(
        () ->
            bulkTaskService.apply(
                List.of(t1.getId(), t2.getId()),
                BulkOperation.ADD_TAG,
                null,
                null,
                null,
                tag.getId(),
                adminUserId));

    assertEquals(1, inWorkspace(() -> tagService.tagsForTask(t1.getId())).size());
    assertEquals(1, inWorkspace(() -> tagService.tagsForTask(t2.getId())).size());
  }

  @Test
  void bulkStatusWithoutValueIsRejected() {
    Task t1 = inWorkspace(() -> taskService.createTask(project.getId(), "T1"));
    assertThrows(
        BusinessRuleException.class,
        () ->
            inWorkspace(
                () ->
                    bulkTaskService.apply(
                        List.of(t1.getId()),
                        BulkOperation.STATUS,
                        null,
                        null,
                        null,
                        null,
                        adminUserId)));
  }

  @Test
  void bulkFailureOnOneTaskRollsBackTheWholeBatch() {
    Task t1 = inWorkspace(() -> taskService.createTask(project.getId(), "T1"));
    UUID unknownTaskId = UUID.randomUUID();

    assertThrows(
        ResourceNotFoundException.class,
        () ->
            inWorkspace(
                () ->
                    bulkTaskService.apply(
                        List.of(t1.getId(), unknownTaskId),
                        BulkOperation.STATUS,
                        TaskStatus.REVIEW,
                        null,
                        null,
                        null,
                        adminUserId)));

    // Transaction rollback beklenir: t1'in durumu DEGISMEMIS olmali.
    Task reloaded = inWorkspace(() -> taskService.findByIds(List.of(t1.getId()))).get(0);
    assertEquals(TaskStatus.TO_DO, reloaded.getStatus());
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
}
