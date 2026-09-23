package com.app.tracker.workspace.controller;

import com.app.tracker.workspace.dto.AddWorkspaceMemberRequest;
import com.app.tracker.workspace.dto.ChangeWorkspaceMemberRoleRequest;
import com.app.tracker.workspace.dto.WorkspaceMemberResponse;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMemberService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aktif workspace'in uyeligi (X-Workspace-Id ile secilir). Ekleme/rol degistirme/cikarma yalniz
 * ADMIN — listeleme herhangi bir uye (diger GET'lerle AYNI desen, membership WorkspaceContextFilter
 * tarafindan zaten dogrulanmis).
 */
@RestController
@RequestMapping("/api/v1/workspaces/members")
public class WorkspaceMemberController {

  private static final String ADMIN_ONLY =
      "@securityGuard.hasCurrentWorkspaceRole('" + WorkspaceRole.ADMIN + "')";

  private final WorkspaceMemberService memberService;

  public WorkspaceMemberController(WorkspaceMemberService memberService) {
    this.memberService = memberService;
  }

  @PostMapping
  @PreAuthorize(ADMIN_ONLY)
  public ResponseEntity<WorkspaceMemberResponse> add(
      @Valid @RequestBody AddWorkspaceMemberRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(memberService.addMember(request.email(), request.role()));
  }

  @GetMapping
  public List<WorkspaceMemberResponse> list() {
    return memberService.listMembers();
  }

  @PatchMapping("/{userId}")
  @PreAuthorize(ADMIN_ONLY)
  public WorkspaceMemberResponse changeRole(
      @PathVariable UUID userId, @Valid @RequestBody ChangeWorkspaceMemberRoleRequest request) {
    return memberService.changeRole(userId, request.role());
  }

  @DeleteMapping("/{userId}")
  @PreAuthorize(ADMIN_ONLY)
  public ResponseEntity<Void> remove(@PathVariable UUID userId) {
    memberService.removeMember(userId);
    return ResponseEntity.noContent().build();
  }
}
