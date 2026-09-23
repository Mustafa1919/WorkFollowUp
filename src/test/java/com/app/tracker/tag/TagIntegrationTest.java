package com.app.tracker.tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.tag.dto.TagResponse;
import com.app.tracker.tag.model.Tag;
import com.app.tracker.tag.service.TagService;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RAKIP_ANALIZI.md Bolum 3 — Tags/Labels: workspace duzeyinde CRUD, essizlik (case-insensitive),
 * gorev atama/kaldirma idempotency'si ve ON DELETE CASCADE. RLS izolasyonu ayri sinifta
 * (TagRlsIsolationTest, RlsIsolationIntegrationTest ile ayni desen).
 */
@SpringBootTest
@AutoConfigureMockMvc
class TagIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private TagService tagService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private MockMvc mockMvc;

  private UUID workspaceId;
  private Project project;
  private String adminToken;
  private UUID adminUserId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Tag WS"));
    project = inWorkspace(() -> projectService.createProject("TAG", "Tag Project"));
    String email = "tag-owner-" + UUID.randomUUID() + "@tracker.local";
    adminUserId = authService.register(email, PASSWORD, "Owner").getId();
    membershipService.addMember(workspaceId, adminUserId, WorkspaceRole.ADMIN);
    adminToken = authService.login(email, PASSWORD, "127.0.0.1").accessToken();
  }

  // ---- CRUD ----

  @Test
  void createListRenameAndDeleteTag() {
    Tag created = inWorkspace(() -> tagService.createTag("Bug", "#FF0000"));
    assertEquals("Bug", created.getName());
    assertEquals("#FF0000", created.getColor());

    List<Tag> afterCreate = inWorkspace(() -> tagService.listTags());
    assertEquals(1, afterCreate.size());

    Tag renamed = inWorkspace(() -> tagService.update(created.getId(), "Defect", "#00FF00"));
    assertEquals("Defect", renamed.getName());
    assertEquals("#00FF00", renamed.getColor());

    inWorkspace(
        () -> {
          tagService.deleteTag(created.getId());
          return null;
        });
    assertTrue(inWorkspace(() -> tagService.listTags()).isEmpty());
  }

  @Test
  void nameIsTrimmedBeforeStoring() {
    Tag created = inWorkspace(() -> tagService.createTag("  Feature  ", "#123456"));
    assertEquals("Feature", created.getName());
  }

  @Test
  void duplicateNameIsRejectedCaseInsensitiveButRenamingToSameNameIsAllowed() {
    Tag bug = inWorkspace(() -> tagService.createTag("Bug", "#FF0000"));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> tagService.createTag("bug", "#00FF00")));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> tagService.createTag("BUG", "#00FF00")));

    // Kendi ismine (farkli harf durumuyla) yeniden adlandirma no-op sayilmali, kendi essizlik
    // kontrolune takilmamali.
    Tag unchanged = inWorkspace(() -> tagService.update(bug.getId(), "BUG", "#0000FF"));
    assertEquals("BUG", unchanged.getName());
    assertEquals("#0000FF", unchanged.getColor());

    // Baska bir etiketi cakisan isme cevirmek reddedilir.
    Tag feature = inWorkspace(() -> tagService.createTag("Feature", "#111111"));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> tagService.update(feature.getId(), "bug", "#222222")));
  }

  @Test
  void invalidPayloadIsRejectedOverHttp() throws Exception {
    assertEquals(
        422,
        status(
            post("/api/v1/tags")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"color\":\"#FF0000\"}")));
    assertEquals(
        422,
        status(
            post("/api/v1/tags")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bug\",\"color\":\"red\"}")));
    assertEquals(
        422,
        status(
            post("/api/v1/tags")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bug\",\"color\":\"#GGGGGG\"}")));
  }

  // ---- gorev atamasi ----

  @Test
  void assignAndUnassignAreIdempotent() {
    Tag tag = inWorkspace(() -> tagService.createTag("Bug", "#FF0000"));
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T"));

    inWorkspace(() -> tagService.assign(task.getId(), tag.getId()));
    inWorkspace(() -> tagService.assign(task.getId(), tag.getId())); // ikinci cagri no-op

    List<TagResponse> tags = inWorkspace(() -> tagService.tagsForTask(task.getId()));
    assertEquals(1, tags.size());
    assertEquals(tag.getId(), tags.get(0).id());

    inWorkspace(() -> tagService.unassign(task.getId(), tag.getId()));
    inWorkspace(() -> tagService.unassign(task.getId(), tag.getId())); // ikinci cagri no-op

    assertTrue(inWorkspace(() -> tagService.tagsForTask(task.getId())).isEmpty());
  }

  @Test
  void assigningUnknownTagOrTaskIsNotFound() {
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T"));
    Tag tag = inWorkspace(() -> tagService.createTag("Bug", "#FF0000"));

    assertThrows(
        ResourceNotFoundException.class,
        () -> inWorkspace(() -> tagService.assign(task.getId(), UUID.randomUUID())));
    assertThrows(
        ResourceNotFoundException.class,
        () -> inWorkspace(() -> tagService.assign(UUID.randomUUID(), tag.getId())));
  }

  @Test
  void approvedTaskLocksTagAssignment() {
    Tag tag = inWorkspace(() -> tagService.createTag("Bug", "#FF0000"));
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T"));
    inWorkspace(() -> taskService.updateStatus(task.getId(), TaskStatus.DONE, adminUserId));
    inWorkspace(() -> taskService.approve(task.getId(), adminUserId));

    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> tagService.assign(task.getId(), tag.getId())));

    // Onaydan ONCE atanmis bir etiketi de kaldiramaz (updateStatus/updateDueDate ile ayni kilit).
    Task task2 = inWorkspace(() -> taskService.createTask(project.getId(), "T2"));
    inWorkspace(() -> tagService.assign(task2.getId(), tag.getId()));
    inWorkspace(() -> taskService.updateStatus(task2.getId(), TaskStatus.DONE, adminUserId));
    inWorkspace(() -> taskService.approve(task2.getId(), adminUserId));
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> tagService.unassign(task2.getId(), tag.getId())));
  }

  @Test
  void deletingTagCascadesTaskAssociation() {
    Tag tag = inWorkspace(() -> tagService.createTag("Bug", "#FF0000"));
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T"));
    inWorkspace(() -> tagService.assign(task.getId(), tag.getId()));
    assertEquals(1, inWorkspace(() -> tagService.tagsForTask(task.getId())).size());

    inWorkspace(
        () -> {
          tagService.deleteTag(tag.getId());
          return null;
        });

    assertTrue(inWorkspace(() -> tagService.tagsForTask(task.getId())).isEmpty());
  }

  @Test
  void batchLoadingMatchesPerTaskLoadingAndAvoidsMissingEntries() {
    Tag bug = inWorkspace(() -> tagService.createTag("Bug", "#FF0000"));
    Tag feature = inWorkspace(() -> tagService.createTag("Feature", "#00FF00"));
    Task withTags = inWorkspace(() -> taskService.createTask(project.getId(), "with-tags"));
    Task withoutTags = inWorkspace(() -> taskService.createTask(project.getId(), "without-tags"));
    inWorkspace(() -> tagService.assign(withTags.getId(), bug.getId()));
    inWorkspace(() -> tagService.assign(withTags.getId(), feature.getId()));

    Map<UUID, List<TagResponse>> batch =
        inWorkspace(() -> tagService.tagsForTasks(List.of(withTags.getId(), withoutTags.getId())));

    assertEquals(2, batch.get(withTags.getId()).size());
    assertTrue(batch.getOrDefault(withoutTags.getId(), List.of()).isEmpty());
  }

  // ---- gorev cevabinda etiketler (uctan uca HTTP) ----

  @Test
  void taskResponseIncludesAssignedTagsOverHttp() throws Exception {
    Tag tag = inWorkspace(() -> tagService.createTag("Bug", "#FF0000"));
    Task task = inWorkspace(() -> taskService.createTask(project.getId(), "T"));
    inWorkspace(() -> tagService.assign(task.getId(), tag.getId()));

    String body =
        mockMvc
            .perform(
                get("/api/v1/projects/" + project.getId() + "/tasks")
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Workspace-Id", workspaceId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertTrue(body.contains("\"name\":\"Bug\""));
    assertTrue(body.contains("\"color\":\"#FF0000\""));
  }

  private int status(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    return mockMvc
        .perform(
            request
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Workspace-Id", workspaceId.toString()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }
}
