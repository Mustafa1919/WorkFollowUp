package com.app.tracker.workspace.controller;

import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.workspace.dto.CreateWorkspaceRequest;
import com.app.tracker.workspace.dto.WorkspaceMembershipResponse;
import com.app.tracker.workspace.dto.WorkspaceResponse;
import com.app.tracker.workspace.model.Workspace;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipQueryService;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PHASE_1_DETAILED_DESIGN.md Bolum 4 — {@code POST /api/v1/workspaces}. */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

  private final WorkspaceService workspaceService;
  private final WorkspaceMembershipService membershipService;

  private final WorkspaceMembershipQueryService membershipQueryService;

  public WorkspaceController(
      WorkspaceService workspaceService,
      WorkspaceMembershipService membershipService,
      WorkspaceMembershipQueryService membershipQueryService) {
    this.workspaceService = workspaceService;
    this.membershipService = membershipService;
    this.membershipQueryService = membershipQueryService;
  }

  /** {@code X-Workspace-Id} GEREKTIRMEZ: istemci secimi bu listeden yapar. */
  @GetMapping
  public List<WorkspaceMembershipResponse> listMine() {
    return membershipQueryService.listForUser(CurrentUser.id());
  }

  @PostMapping
  public ResponseEntity<WorkspaceResponse> create(
      @Valid @RequestBody CreateWorkspaceRequest request) {
    Workspace workspace = workspaceService.createWorkspace(UUID.randomUUID(), request.name());
    // Workspace'i yaratan kullanici otomatik olarak WORKSPACE_ADMIN olur.
    membershipService.addMember(workspace.getId(), CurrentUser.id(), WorkspaceRole.ADMIN);
    return ResponseEntity.status(HttpStatus.CREATED).body(WorkspaceResponse.from(workspace));
  }
}
