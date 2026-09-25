package com.app.tracker.retro;

import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.dependency.service.TaskDependencyService.DependencySummary;
import com.app.tracker.retro.dto.RetroItemResponse;
import com.app.tracker.task.dto.TaskResponse;
import com.app.tracker.task.model.Task;
import com.app.tracker.workspace.model.WorkspaceRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dalga 2.4 — veriye dayali retro + retro panosu. Okuma (retro/liste) rol siniri yok (analitik
 * okuma ilkesiyle AYNI); madde ekleme/goreve donusturme diger gorev mutasyonlarinin (WRITE)
 * rolleriyle AYNI; silme sahiplik kontrolu servis katmaninda (SavedView ile AYNI desen, ADMIN
 * istisnasi yok).
 */
@RestController
public class RetroController {

  private static final String WRITE =
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')";

  private final RetroService retroService;
  private final RetroItemService retroItemService;

  public RetroController(RetroService retroService, RetroItemService retroItemService) {
    this.retroService = retroService;
    this.retroItemService = retroItemService;
  }

  @GetMapping("/api/v1/sprints/{sprintId}/retro")
  public RetroResponse retro(@PathVariable UUID sprintId) {
    return retroService.build(sprintId);
  }

  @GetMapping("/api/v1/sprints/{sprintId}/retro/items")
  public List<RetroItemResponse> items(@PathVariable UUID sprintId) {
    return retroItemService.list(sprintId);
  }

  @PostMapping("/api/v1/sprints/{sprintId}/retro/items")
  @PreAuthorize(WRITE)
  public ResponseEntity<RetroItemResponse> createItem(
      @PathVariable UUID sprintId,
      @jakarta.validation.Valid @RequestBody RetroItemRequest request) {
    RetroItemResponse created =
        retroItemService.create(sprintId, request.kind(), request.body(), CurrentUser.id());
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @DeleteMapping("/api/v1/retro-items/{id}")
  public ResponseEntity<Void> deleteItem(@PathVariable UUID id) {
    retroItemService.delete(id, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/api/v1/retro-items/{id}/convert-to-task")
  @PreAuthorize(WRITE)
  public ResponseEntity<TaskResponse> convertToTask(@PathVariable UUID id) {
    Task task = retroItemService.convertToTask(id, CurrentUser.id());
    // Yeni gorevin hicbir etiketi/story point'i/alt gorevi/bagimliligi/yorumu olamaz
    // (TaskController
    // #create ile AYNI desen).
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(TaskResponse.from(task, List.of(), null, 0, 0, DependencySummary.empty(), 0));
  }

  public record RetroItemRequest(
      @NotBlank @Pattern(regexp = "went_well|improve|action") String kind,
      @NotBlank @Size(max = 2000) String body) {}
}
