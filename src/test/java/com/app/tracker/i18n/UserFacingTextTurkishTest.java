package com.app.tracker.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.app.tracker.core.config.OpenApiConfig;
import com.app.tracker.retro.RetroItemRepository;
import com.app.tracker.retro.RetroItemService;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.repository.SprintRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskEventRepository;
import com.app.tracker.task.repository.TaskRepository;
import com.app.tracker.task.service.TaskActivityService;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserFacingTextTurkishTest {
  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant TIME = Instant.parse("2026-11-01T00:00:00Z");

  @Test
  void K1_openApiDescriptionContainsTurkishCharacters() {
    assertThat(new OpenApiConfig().trackerOpenApi().getInfo().getDescription())
        .isNotBlank()
        .doesNotContain("Cok", "kiracili", "dogrulama", "alinan", "gonderilir", "bagli", "ayrica");
  }

  @Test
  void K1_openApiDescriptionMatchesGlossary() {
    assertThat(
            new OpenApiConfig()
                .trackerOpenApi()
                .getInfo()
                .getDescription()
                .replace("X-Workspace-Id", "")
                .replace("erişim belirteci (access token)", "erişim belirteci"))
        .isNotBlank()
        .doesNotContainIgnoringCase("workspace", "header", "access token");
  }

  @Test
  void R1_openApiTechnicalHeaderKeyIsPreserved() {
    assertThat(
            new OpenApiConfig()
                .trackerOpenApi()
                .getComponents()
                .getSecuritySchemes()
                .get("workspaceHeader")
                .getName())
        .isEqualTo("X-Workspace-Id");
  }

  private String activityName(boolean known) {
    var events = mock(TaskEventRepository.class);
    var tasks = mock(TaskRepository.class);
    var users = mock(UserRepository.class);
    when(tasks.findById(ID)).thenReturn(Optional.of(mock(Task.class)));
    when(events.findFirstPage(ID, 31))
        .thenReturn(
            List.of(
                new TaskEventRepository.ActivityEntry(
                    ID, ID, "status_changed", "status", "To Do", "Done", TIME)));
    User user = mock(User.class);
    when(user.getId()).thenReturn(ID);
    when(user.getFullName()).thenReturn("Ayşe Öztürk");
    when(users.findAllById(any())).thenReturn(known ? List.of(user) : List.of());
    var result =
        new TaskActivityService(events, tasks, users).listActivity(ID, 30, null).data().getFirst();
    assertThat(result.oldValue()).isEqualTo("To Do");
    assertThat(result.newValue()).isEqualTo("Done");
    return result.actorName();
  }

  private String retroName(boolean known, boolean create) {
    var items = mock(RetroItemRepository.class);
    var sprints = mock(SprintRepository.class);
    var users = mock(UserRepository.class);
    when(sprints.findById(ID)).thenReturn(Optional.of(mock(Sprint.class)));
    var row = new RetroItemRepository.Row(ID, ID, "went_well", "İyi gitti", ID, null, TIME);
    when(items.findBySprintId(ID)).thenReturn(List.of(row));
    when(items.findById(any())).thenReturn(Optional.of(row));
    var user = mock(User.class);
    when(user.getId()).thenReturn(ID);
    when(user.getFullName()).thenReturn("Ayşe Öztürk");
    when(users.findAllById(any())).thenReturn(known ? List.of(user) : List.of());
    when(users.findById(ID)).thenReturn(known ? Optional.of(user) : Optional.empty());
    var service = new RetroItemService(items, sprints, mock(TaskService.class), users);
    return (create ? service.create(ID, "went_well", "İyi gitti", ID) : service.list(ID).getFirst())
        .authorName();
  }

  @Test
  void K1_R2_activityPreservesKnownUserAndRawStatus() {
    assertThat(activityName(true)).isEqualTo("Ayşe Öztürk");
  }

  @Test
  void K1_unknownActivityUserIsTurkish() {
    assertThat(activityName(false)).isEqualTo("Bilinmeyen kullanıcı");
  }

  @Test
  void K1_retroListPreservesKnownUser() {
    assertThat(retroName(true, false)).isEqualTo("Ayşe Öztürk");
  }

  @Test
  void K1_unknownRetroListUserIsTurkish() {
    assertThat(retroName(false, false)).isEqualTo("Bilinmeyen kullanıcı");
  }

  @Test
  void K1_retroCreationPreservesKnownUser() {
    assertThat(retroName(true, true)).isEqualTo("Ayşe Öztürk");
  }

  @Test
  void K1_unknownRetroCreationUserIsTurkish() {
    assertThat(retroName(false, true)).isEqualTo("Bilinmeyen kullanıcı");
  }

  @Test
  void K7_threeFallbackNamesAreTurkishAndKnownNamesArePreserved() {
    assertThat(activityName(false)).isEqualTo("Bilinmeyen kullanıcı");
    assertThat(retroName(false, false)).isEqualTo("Bilinmeyen kullanıcı");
    assertThat(retroName(false, true)).isEqualTo("Bilinmeyen kullanıcı");
    assertThat(activityName(true)).isEqualTo("Ayşe Öztürk");
    assertThat(retroName(true, false)).isEqualTo("Ayşe Öztürk");
    assertThat(retroName(true, true)).isEqualTo("Ayşe Öztürk");
  }

  @Test
  void K8_visibleOpenApiTextIsTurkishAndTechnicalSchemasAreUnchanged() {
    var api = new OpenApiConfig().trackerOpenApi();
    assertThat(api.getInfo().getTitle()).isEqualTo("WorkFollowUp API");
    assertThat(api.getInfo().getDescription())
        .contains("Kimlik doğrulama", "çalışma alanı", "başlığ");
    assertThat(api.getComponents().getSecuritySchemes())
        .containsOnlyKeys("bearerAuth", "workspaceHeader");
    assertThat(api.getComponents().getSecuritySchemes().get("bearerAuth").getScheme())
        .isEqualTo("bearer");
    assertThat(api.getComponents().getSecuritySchemes().get("workspaceHeader").getName())
        .isEqualTo("X-Workspace-Id");
  }
}
