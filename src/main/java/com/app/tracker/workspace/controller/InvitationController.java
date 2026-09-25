package com.app.tracker.workspace.controller;

import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.workspace.dto.AcceptInvitationResponse;
import com.app.tracker.workspace.dto.InvitationPreviewResponse;
import com.app.tracker.workspace.service.WorkspaceInvitationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Davet linki ile calisir, workspace secimiyle (X-Workspace-Id) DEGIL — token hangi workspace'e ait
 * oldugunu kendi tasir. {@code GET} public (SecurityConfig'te permitAll, henuz giris yapmamis
 * birinin davet onizlemesini gorebilmesi icin); {@code POST .../accept} kimlik dogrulamasi ister
 * ama workspace header'i GEREKMEZ.
 */
@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {

  private final WorkspaceInvitationService invitationService;

  public InvitationController(WorkspaceInvitationService invitationService) {
    this.invitationService = invitationService;
  }

  @GetMapping("/{token}")
  public InvitationPreviewResponse preview(@PathVariable String token) {
    return invitationService.preview(token);
  }

  @PostMapping("/{token}/accept")
  public AcceptInvitationResponse accept(@PathVariable String token) {
    return invitationService.accept(token, CurrentUser.id());
  }
}
