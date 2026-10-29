package com.app.tracker.user.controller;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.user.dto.MeResponse;
import com.app.tracker.user.dto.MyPermissionsResponse;
import com.app.tracker.user.dto.PrivacyNoticeAcceptanceRequest;
import com.app.tracker.user.service.MeService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kimlik uclari (ADR-0019). {@code /me} {@code X-Workspace-Id} GEREKTIRMEZ; {@code /me/permissions}
 * gerektirir. Aydinlatma onayi yalniz JWT oturumundan yapilabilir.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

  private final MeService meService;

  public MeController(MeService meService) {
    this.meService = meService;
  }

  @GetMapping
  public MeResponse me() {
    return MeResponse.from(meService.getUser(CurrentUser.id()));
  }

  @GetMapping("/permissions")
  public MyPermissionsResponse permissions() {
    UUID workspaceId = TenantContext.getWorkspaceId();
    if (workspaceId == null) {
      throw new BusinessRuleException("X-Workspace-Id header'i zorunludur.");
    }
    return MyPermissionsResponse.of(meService.getRole(CurrentUser.id(), workspaceId));
  }

  @PostMapping("/privacy-notice-acceptance")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("!hasAuthority('AUTH_PAT')")
  public void acceptPrivacyNotice(@Valid @RequestBody PrivacyNoticeAcceptanceRequest request) {
    meService.acceptPrivacyNotice(CurrentUser.id(), request.version());
  }
}
