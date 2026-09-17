package com.app.tracker.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * PHASE_1_DETAILED_DESIGN.md Bolum "RLS'i Test Etmeden Prod'a Cikmayin" — bu iki test, mimarinin en
 * kritik guvenlik varsayimini dogrular: (1) workspace A context'i ile workspace B'nin task'ina
 * erisilemez (projectId eslesse bile), (2) context hic set edilmemisse hicbir satir donmez
 * (fail-closed).
 */
@SpringBootTest
class RlsIsolationIntegrationTest extends AbstractIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TenantExecutor tenantExecutor;

  @Test
  void otherWorkspacesTasksAreInvisible() {
    UUID workspaceA = UUID.randomUUID();
    UUID workspaceB = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceA, "Workspace A"));
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceB, "Workspace B"));

    Project projectA =
        tenantExecutor.runAs(workspaceA, () -> projectService.createProject("ENG", "Engineering"));
    Project projectB =
        tenantExecutor.runAs(workspaceB, () -> projectService.createProject("MKT", "Marketing"));

    tenantExecutor.runAs(workspaceA, () -> taskService.createTask(projectA.getId(), "A-task"));
    tenantExecutor.runAs(workspaceB, () -> taskService.createTask(projectB.getId(), "B-task"));

    // Workspace A context'i ile workspace B'nin projesindeki task'lar sorgulanir: projects
    // tablosu da RLS'e tabi oldugundan projectB'nin KENDISI bile A icin gorunmez — TaskService
    // bunu "proje bulunamadi" olarak yorumlar (veri sizdirmak yerine fail-closed 404 esdegeri).
    assertThrows(
        BusinessRuleException.class,
        () ->
            tenantExecutor.runAs(
                workspaceA, () -> taskService.listTasks(projectB.getId(), 20, null)));

    PageResponse<Task> ownTasks =
        tenantExecutor.runAs(workspaceA, () -> taskService.listTasks(projectA.getId(), 20, null));
    assertEquals(1, ownTasks.data().size());
    assertEquals("A-task", ownTasks.data().get(0).getTitle());
  }

  @Test
  void noTenantContextMeansNoRows() {
    UUID workspaceA = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceA, "Workspace A"));
    Project projectA =
        tenantExecutor.runAs(workspaceA, () -> projectService.createProject("ENG", "Engineering"));
    tenantExecutor.runAs(workspaceA, () -> taskService.createTask(projectA.getId(), "A-task"));

    // Context set edilmemisse projectRepository.findById de RLS'ten gecer ve BOS doner; TaskService
    // bunu "proje bulunamadi" olarak yorumlar — veri sizdirmak yerine fail-closed 404 esdegeri.
    assertThrows(
        BusinessRuleException.class,
        () -> tenantExecutor.runAs(null, () -> taskService.listTasks(projectA.getId(), 20, null)));
  }
}
