package com.app.tracker.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.tag.model.Tag;
import com.app.tracker.tag.service.TagService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * RlsIsolationIntegrationTest ile AYNI desen (task/RLS'e uygulanan): workspace A'nin etiketleri
 * workspace B'ye gorunmez ve B, A'nin etiketini kendi gorevine ATAYAMAZ — id'yi taklit etse bile
 * RLS satiri gostermedigi icin TagService bunu "etiket bulunamadi" olarak yorumlar (404 esdegeri).
 */
@SpringBootTest
class TagRlsIsolationTest extends AbstractIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TagService tagService;
  @Autowired private TenantExecutor tenantExecutor;

  @Test
  void otherWorkspacesTagsAreInvisible() {
    UUID workspaceA = UUID.randomUUID();
    UUID workspaceB = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceA, "WS A"));
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceB, "WS B"));

    tenantExecutor.runAs(workspaceA, () -> tagService.createTag("A-only", "#111111"));
    tenantExecutor.runAs(workspaceB, () -> tagService.createTag("B-only", "#222222"));

    List<Tag> tagsForA = tenantExecutor.runAs(workspaceA, () -> tagService.listTags());
    List<Tag> tagsForB = tenantExecutor.runAs(workspaceB, () -> tagService.listTags());
    assertEquals(List.of("A-only"), tagsForA.stream().map(Tag::getName).toList());
    assertEquals(List.of("B-only"), tagsForB.stream().map(Tag::getName).toList());
  }

  @Test
  void workspaceCannotAssignAnotherWorkspacesTagToItsOwnTask() {
    UUID workspaceA = UUID.randomUUID();
    UUID workspaceB = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceA, "WS A"));
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceB, "WS B"));

    Tag tagB = tenantExecutor.runAs(workspaceB, () -> tagService.createTag("B-secret", "#333333"));
    Project projectA =
        tenantExecutor.runAs(workspaceA, () -> projectService.createProject("ENG", "Eng"));
    Task taskA =
        tenantExecutor.runAs(workspaceA, () -> taskService.createTask(projectA.getId(), "A-task"));

    // B'nin etiket id'sini biliyor olsa bile (ornegin URL'den tahmin ederek), A context'inde bu
    // id RLS'ten hic gecmez -> TagService "etiket bulunamadi" firlatir, veri sizdirmaz.
    assertThrows(
        ResourceNotFoundException.class,
        () ->
            tenantExecutor.runAs(workspaceA, () -> tagService.assign(taskA.getId(), tagB.getId())));

    assertTrue(
        tenantExecutor.runAs(workspaceA, () -> tagService.tagsForTask(taskA.getId())).isEmpty());
  }

  @Test
  void noTenantContextMeansNoTagRows() {
    UUID workspaceA = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceA, "WS A"));
    Tag tag = tenantExecutor.runAs(workspaceA, () -> tagService.createTag("A-only", "#111111"));

    assertTrue(tenantExecutor.runAs(null, () -> tagService.listTags()).isEmpty());
    assertThrows(
        ResourceNotFoundException.class,
        () ->
            tenantExecutor.runAs(
                null,
                () -> {
                  tagService.deleteTag(tag.getId());
                  return null;
                }));
  }
}
