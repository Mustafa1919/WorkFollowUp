package com.app.tracker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.dependency.dto.TaskRefResponse;
import com.app.tracker.dependency.service.TaskDependencyService;
import com.app.tracker.dependency.service.TaskDependencyService.DependencySummary;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * RAKIP_ANALIZI.md Bolum 3 — Subtask (tek seviye, ayni proje) + Dependency (bilgilendirici,
 * workspace geneli). TagIntegrationTest ile AYNI desen (servis katmani + tenantExecutor.runAs).
 */
@SpringBootTest
class TaskSubtaskAndDependencyIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TaskDependencyService taskDependencyService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;

  private UUID workspaceId;
  private Project project;
  private UUID adminUserId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Sub WS"));
    project = inWorkspace(() -> projectService.createProject("SUB", "Subtask Project"));
    String email = "sub-owner-" + UUID.randomUUID() + "@tracker.local";
    adminUserId = authService.register(email, PASSWORD, "Owner").getId();
    membershipService.addMember(workspaceId, adminUserId, WorkspaceRole.ADMIN);
  }

  // ---- Subtask ----

  @Test
  void setAndRemoveParentIsIdempotent() {
    Task parent = inWorkspace(() -> taskService.createTask(project.getId(), "Parent"));
    Task child = inWorkspace(() -> taskService.createTask(project.getId(), "Child"));

    Task linked =
        inWorkspace(() -> taskService.setParent(child.getId(), parent.getId(), adminUserId));
    assertEquals(parent.getId(), linked.getParentTaskId());
    // ikinci cagri no-op
    Task linkedAgain =
        inWorkspace(() -> taskService.setParent(child.getId(), parent.getId(), adminUserId));
    assertEquals(parent.getId(), linkedAgain.getParentTaskId());

    Task removed = inWorkspace(() -> taskService.removeParent(child.getId(), adminUserId));
    assertNull(removed.getParentTaskId());
    // ikinci cagri no-op
    Task removedAgain = inWorkspace(() -> taskService.removeParent(child.getId(), adminUserId));
    assertNull(removedAgain.getParentTaskId());
  }

  @Test
  void taskCannotBeItsOwnParent() {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T"));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskService.setParent(task.getId(), task.getId(), adminUserId)));
  }

  @Test
  void subtaskDepthIsLimitedToOneLevel() {
    Task grandparent = inWorkspace(() -> taskService.createTask(project.getId(), "GP"));
    Task parent = inWorkspace(() -> taskService.createTask(project.getId(), "P"));
    Task child = inWorkspace(() -> taskService.createTask(project.getId(), "C"));
    inWorkspace(() -> taskService.setParent(parent.getId(), grandparent.getId(), adminUserId));

    // parent'in kendi parent'i var -> baska bir gorevin parent'i olamaz
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskService.setParent(child.getId(), parent.getId(), adminUserId)));

    // grandparent'in zaten cocugu var -> baska bir gorevin subtask'i olamaz
    Task another = inWorkspace(() -> taskService.createTask(project.getId(), "A"));
    assertThrows(
        BusinessRuleException.class,
        () ->
            inWorkspace(
                () -> taskService.setParent(grandparent.getId(), another.getId(), adminUserId)));
  }

  @Test
  void subtaskMustBeInSameProject() {
    Project other = inWorkspace(() -> projectService.createProject("OTH", "Other Project"));
    Task parent = inWorkspace(() -> taskService.createTask(project.getId(), "Parent"));
    Task child = inWorkspace(() -> taskService.createTask(other.getId(), "Child"));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskService.setParent(child.getId(), parent.getId(), adminUserId)));
  }

  @Test
  void deletingTaskWithChildrenIsRejected() {
    Task parent = inWorkspace(() -> taskService.createTask(project.getId(), "Parent"));
    Task child = inWorkspace(() -> taskService.createTask(project.getId(), "Child"));
    inWorkspace(() -> taskService.setParent(child.getId(), parent.getId(), adminUserId));

    assertThrows(
        BusinessRuleException.class,
        () ->
            inWorkspace(
                () -> {
                  taskService.deleteTask(parent.getId(), adminUserId);
                  return null;
                }));
  }

  @Test
  void approvedTaskLocksParentChange() {
    Task parent = inWorkspace(() -> taskService.createTask(project.getId(), "Parent"));
    Task child = inWorkspace(() -> taskService.createTask(project.getId(), "Child"));
    inWorkspace(() -> taskService.updateStatus(child.getId(), TaskStatus.DONE, adminUserId));
    inWorkspace(() -> taskService.approve(child.getId(), adminUserId));

    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskService.setParent(child.getId(), parent.getId(), adminUserId)));
  }

  @Test
  void subtaskCountsAreBatchedCorrectly() {
    Task parent = inWorkspace(() -> taskService.createTask(project.getId(), "Parent"));
    Task child1 = inWorkspace(() -> taskService.createTask(project.getId(), "C1"));
    Task child2 = inWorkspace(() -> taskService.createTask(project.getId(), "C2"));
    inWorkspace(() -> taskService.setParent(child1.getId(), parent.getId(), adminUserId));
    inWorkspace(() -> taskService.setParent(child2.getId(), parent.getId(), adminUserId));
    inWorkspace(() -> taskService.updateStatus(child1.getId(), TaskStatus.DONE, adminUserId));

    Map<UUID, int[]> counts = inWorkspace(() -> taskService.subtaskCounts(List.of(parent.getId())));
    assertEquals(2, counts.get(parent.getId())[0]);
    assertEquals(1, counts.get(parent.getId())[1]);
  }

  // ---- Dependency ----

  @Test
  void linkAndUnlinkAreIdempotent() {
    Task blocked = inWorkspace(() -> taskService.createTask(project.getId(), "Blocked"));
    Task blocking = inWorkspace(() -> taskService.createTask(project.getId(), "Blocking"));

    inWorkspace(() -> taskDependencyService.link(blocked.getId(), blocking.getId()));
    inWorkspace(() -> taskDependencyService.link(blocked.getId(), blocking.getId())); // no-op

    DependencySummary summary =
        inWorkspace(() -> taskDependencyService.dependenciesForTask(blocked.getId()));
    assertEquals(1, summary.blockedBy().size());
    assertEquals(blocking.getId(), summary.blockedBy().get(0).id());

    DependencySummary blockingSummary =
        inWorkspace(() -> taskDependencyService.dependenciesForTask(blocking.getId()));
    assertEquals(1, blockingSummary.blocking().size());
    assertEquals(blocked.getId(), blockingSummary.blocking().get(0).id());

    inWorkspace(() -> taskDependencyService.unlink(blocked.getId(), blocking.getId()));
    inWorkspace(() -> taskDependencyService.unlink(blocked.getId(), blocking.getId())); // no-op
    assertTrue(
        inWorkspace(() -> taskDependencyService.dependenciesForTask(blocked.getId()))
            .blockedBy()
            .isEmpty());
  }

  @Test
  void taskCannotBlockItself() {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T"));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskDependencyService.link(task.getId(), task.getId())));
  }

  @Test
  void reversePairIsRejected() {
    Task a = inWorkspace(() -> taskService.createTask(project.getId(), "A"));
    Task b = inWorkspace(() -> taskService.createTask(project.getId(), "B"));
    inWorkspace(() -> taskDependencyService.link(a.getId(), b.getId())); // b blocks a

    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskDependencyService.link(b.getId(), a.getId()))); // a blocks b
  }

  @Test
  void dependencyCanCrossProjects() {
    Project other = inWorkspace(() -> projectService.createProject("OTH2", "Other Project 2"));
    Task blocked = inWorkspace(() -> taskService.createTask(project.getId(), "Blocked"));
    Task blocking = inWorkspace(() -> taskService.createTask(other.getId(), "Blocking"));

    inWorkspace(() -> taskDependencyService.link(blocked.getId(), blocking.getId()));
    List<TaskRefResponse> blockedBy =
        inWorkspace(() -> taskDependencyService.dependenciesForTask(blocked.getId())).blockedBy();
    assertEquals(1, blockedBy.size());
  }

  @Test
  void unknownTaskIsNotFound() {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T"));
    assertThrows(
        ResourceNotFoundException.class,
        () -> inWorkspace(() -> taskDependencyService.link(task.getId(), UUID.randomUUID())));
  }

  @Test
  void approvedTaskLocksDependencyChange() {
    Task blocked = inWorkspace(() -> taskService.createTask(project.getId(), "Blocked"));
    Task blocking = inWorkspace(() -> taskService.createTask(project.getId(), "Blocking"));
    inWorkspace(() -> taskService.updateStatus(blocked.getId(), TaskStatus.DONE, adminUserId));
    inWorkspace(() -> taskService.approve(blocked.getId(), adminUserId));

    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> taskDependencyService.link(blocked.getId(), blocking.getId())));
  }

  @Test
  void openBlockerDoesNotPreventStatusChange() {
    // Kullanici karari: dependency sadece bilgilendirici, durum gecisini engellemez.
    Task blocked = inWorkspace(() -> taskService.createTask(project.getId(), "Blocked"));
    Task blocking = inWorkspace(() -> taskService.createTask(project.getId(), "Blocking"));
    inWorkspace(() -> taskDependencyService.link(blocked.getId(), blocking.getId()));

    Task done =
        inWorkspace(() -> taskService.updateStatus(blocked.getId(), TaskStatus.DONE, adminUserId));
    assertEquals(TaskStatus.DONE, done.getStatus());
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }
}
