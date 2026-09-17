package com.app.tracker.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskTenancyDemoService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * PHASE_1_DETAILED_DESIGN.md Bolum "RLS'i Test Etmeden Prod'a Cikmayin" — bu iki test, mimarinin en
 * kritik guvenlik varsayimini dogrular: (1) workspace A context'i ile workspace B'nin task'ina
 * erisilemez, (2) context hic set edilmemisse hicbir satir donmez (fail-closed).
 */
@SpringBootTest
class RlsIsolationIntegrationTest extends AbstractIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private TaskTenancyDemoService taskTenancyDemoService;
  @Autowired private TenantExecutor tenantExecutor;

  @Test
  void otherWorkspacesTasksAreInvisible() {
    UUID workspaceA = UUID.randomUUID();
    UUID workspaceB = UUID.randomUUID();

    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceA, "Workspace A"));
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceB, "Workspace B"));

    UUID projectA = UUID.randomUUID();
    UUID projectB = UUID.randomUUID();
    tenantExecutor.runAs(
        workspaceA,
        () -> taskTenancyDemoService.createProject(projectA, workspaceA, "ENG", "Engineering"));
    tenantExecutor.runAs(
        workspaceB,
        () -> taskTenancyDemoService.createProject(projectB, workspaceB, "MKT", "Marketing"));

    tenantExecutor.runAs(
        workspaceA,
        () -> taskTenancyDemoService.createTask(UUID.randomUUID(), workspaceA, projectA, "A-task"));
    tenantExecutor.runAs(
        workspaceB,
        () -> taskTenancyDemoService.createTask(UUID.randomUUID(), workspaceB, projectB, "B-task"));

    List<Task> visibleFromA =
        tenantExecutor.runAs(workspaceA, taskTenancyDemoService::findAllVisibleTasks);

    assertEquals(1, visibleFromA.size());
    assertEquals("A-task", visibleFromA.get(0).getTitle());
  }

  @Test
  void noTenantContextMeansNoRows() {
    UUID workspaceA = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceA, "Workspace A"));

    UUID projectA = UUID.randomUUID();
    tenantExecutor.runAs(
        workspaceA,
        () -> taskTenancyDemoService.createProject(projectA, workspaceA, "ENG", "Engineering"));
    tenantExecutor.runAs(
        workspaceA,
        () -> taskTenancyDemoService.createTask(UUID.randomUUID(), workspaceA, projectA, "A-task"));

    List<Task> visibleWithoutContext =
        tenantExecutor.runAs(null, taskTenancyDemoService::findAllVisibleTasks);

    assertTrue(visibleWithoutContext.isEmpty());
  }
}
