package com.app.tracker.project.controller;

import com.app.tracker.project.dto.CreateProjectRequest;
import com.app.tracker.project.dto.ProjectResponse;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PHASE_1_DETAILED_DESIGN.md Bolum 4 — {@code GET/POST /api/v1/projects}. */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

  private final ProjectService projectService;

  public ProjectController(ProjectService projectService) {
    this.projectService = projectService;
  }

  @PostMapping
  @PreAuthorize("@securityGuard.hasCurrentWorkspaceRole('WORKSPACE_ADMIN', 'MANAGER')")
  public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
    Project project = projectService.createProject(request.key(), request.name());
    return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(project));
  }

  @GetMapping
  public List<ProjectResponse> list() {
    return projectService.listProjects().stream().map(ProjectResponse::from).toList();
  }
}
