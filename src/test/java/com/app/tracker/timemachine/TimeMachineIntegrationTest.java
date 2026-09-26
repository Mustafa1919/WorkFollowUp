package com.app.tracker.timemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.service.SprintService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.model.TaskStatus;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.timemachine.dto.TaskSnapshotResponse;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR-0017 — zaman makinesi. {@code SprintSnapshotRepository}'nin kesit desenini (created_at &lt;=
 * cutoff olan son olay) test eden {@code RetroIntegrationTest} ile AYNI kurulum deseni.
 */
@SpringBootTest
class TimeMachineIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final long STEP_MILLIS = 50;

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private SprintService sprintService;
  @Autowired private TaskService taskService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private TimeMachineService timeMachineService;

  private UUID workspaceId;
  private Project project;
  private UUID actorId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "TM WS"));
    project = inWorkspace(() -> projectService.createProject("TM", "Time Machine Project"));
    actorId = registerMember("tm-owner", WorkspaceRole.ADMIN);
  }

  @Test
  void boardAtReconstructsStatusSprintAndDeletionHistory() throws InterruptedException {
    Instant beforeCreation = tick();
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T1"));
    Instant afterCreation = tick();

    inWorkspace(() -> taskService.updateStatus(task.getId(), TaskStatus.IN_PROGRESS, actorId));
    Instant afterInProgress = tick();

    Sprint sprint = newSprint("S1");
    inWorkspace(() -> taskService.assignSprint(task.getId(), sprint.getId(), actorId));
    Instant afterSprintAssign = tick();

    inWorkspace(() -> taskService.updateStatus(task.getId(), TaskStatus.DONE, actorId));
    Instant afterDone = tick();

    Task toDelete = inWorkspace(() -> taskService.createTask(project.getId(), "T2"));
    Instant afterSecondTaskCreated = tick();
    inWorkspaceVoid(() -> taskService.deleteTask(toDelete.getId(), actorId));
    Instant afterDeletion = tick();

    // Gorev henuz yokken kesit bos.
    assertTrue(
        inWorkspace(() -> timeMachineService.boardAt(project.getId(), beforeCreation)).isEmpty());

    // Olusturulduktan hemen sonra: baslangic durumu, sprint yok.
    TaskSnapshotResponse afterCreate = only(afterCreation, task.getId());
    assertEquals(TaskStatus.TO_DO, afterCreate.status());
    assertNull(afterCreate.sprintId());

    TaskSnapshotResponse inProgressSnapshot = only(afterInProgress, task.getId());
    assertEquals(TaskStatus.IN_PROGRESS, inProgressSnapshot.status());
    assertNull(inProgressSnapshot.sprintId());

    TaskSnapshotResponse sprintAssignedSnapshot = only(afterSprintAssign, task.getId());
    assertEquals(sprint.getId(), sprintAssignedSnapshot.sprintId());
    assertEquals(TaskStatus.IN_PROGRESS, sprintAssignedSnapshot.status());

    TaskSnapshotResponse doneSnapshot = only(afterDone, task.getId());
    assertEquals(TaskStatus.DONE, doneSnapshot.status());

    // Ikinci gorev silinmeden ONCEki kesitte gorunur, silindikten SONRAki kesitte gorunmez.
    List<TaskSnapshotResponse> beforeDeletion =
        inWorkspace(() -> timeMachineService.boardAt(project.getId(), afterSecondTaskCreated));
    assertTrue(beforeDeletion.stream().anyMatch(r -> r.id().equals(toDelete.getId())));

    List<TaskSnapshotResponse> afterDeletionSnapshot =
        inWorkspace(() -> timeMachineService.boardAt(project.getId(), afterDeletion));
    assertFalse(afterDeletionSnapshot.stream().anyMatch(r -> r.id().equals(toDelete.getId())));
  }

  @Test
  void boardAtRejectsFutureCutoff() {
    Instant future = Instant.now().plusSeconds(3600);
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> timeMachineService.boardAt(project.getId(), future)));
  }

  @Test
  void boardAtRejectsUnknownProject() {
    assertThrows(
        ResourceNotFoundException.class,
        () -> inWorkspace(() -> timeMachineService.boardAt(UUID.randomUUID(), Instant.now())));
  }

  // ---- yardimcilar --------------------------------------------------------------------------

  private TaskSnapshotResponse only(Instant cutoff, UUID taskId) {
    List<TaskSnapshotResponse> board =
        inWorkspace(() -> timeMachineService.boardAt(project.getId(), cutoff));
    Optional<TaskSnapshotResponse> found =
        board.stream().filter(r -> r.id().equals(taskId)).findFirst();
    assertTrue(found.isPresent(), "Gorev kesitte bulunamadi: " + taskId);
    return found.get();
  }

  /** Ardisik olaylarin ayni {@code created_at}'e (NOW()) dusme riskini azaltir. */
  private Instant tick() throws InterruptedException {
    Thread.sleep(STEP_MILLIS);
    Instant now = Instant.now();
    Thread.sleep(STEP_MILLIS);
    return now;
  }

  private Sprint newSprint(String name) {
    return inWorkspace(
        () ->
            sprintService.createSprint(
                project.getId(), name, null, LocalDate.of(2099, 1, 1), LocalDate.of(2099, 1, 15)));
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

  private void inWorkspaceVoid(Runnable action) {
    inWorkspace(
        () -> {
          action.run();
          return null;
        });
  }
}
