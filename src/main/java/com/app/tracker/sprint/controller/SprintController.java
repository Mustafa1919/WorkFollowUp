package com.app.tracker.sprint.controller;

import com.app.tracker.sprint.dto.CreateSprintRequest;
import com.app.tracker.sprint.dto.SprintResponse;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.service.SprintService;
import com.app.tracker.workspace.model.WorkspaceRole;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** PHASE_3_DETAILED_DESIGN.md Bolum 1.1 — sprint CRUD ve yasam dongusu. */
@RestController
public class SprintController {

  private static final String MANAGE =
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "')";

  private final SprintService sprintService;

  public SprintController(SprintService sprintService) {
    this.sprintService = sprintService;
  }

  @PostMapping("/api/v1/projects/{projectId}/sprints")
  @PreAuthorize(MANAGE)
  public ResponseEntity<SprintResponse> create(
      @PathVariable UUID projectId, @Valid @RequestBody CreateSprintRequest request) {
    Sprint sprint =
        sprintService.createSprint(
            projectId, request.name(), request.goal(), request.startDate(), request.endDate());
    return ResponseEntity.status(HttpStatus.CREATED).body(SprintResponse.from(sprint));
  }

  @GetMapping("/api/v1/projects/{projectId}/sprints")
  public List<SprintResponse> list(@PathVariable UUID projectId) {
    return sprintService.listSprints(projectId).stream().map(SprintResponse::from).toList();
  }

  @PostMapping("/api/v1/sprints/{sprintId}/start")
  @PreAuthorize(MANAGE)
  public SprintResponse start(@PathVariable UUID sprintId) {
    return SprintResponse.from(sprintService.startSprint(sprintId));
  }

  @PostMapping("/api/v1/sprints/{sprintId}/complete")
  @PreAuthorize(MANAGE)
  public SprintResponse complete(@PathVariable UUID sprintId) {
    return SprintResponse.from(sprintService.completeSprint(sprintId));
  }
}
