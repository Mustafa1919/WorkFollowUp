package com.app.tracker.notification.controller;

import com.app.tracker.notification.dto.SlackIntegrationRequest;
import com.app.tracker.notification.dto.SlackIntegrationResponse;
import com.app.tracker.notification.service.SlackIntegrationService;
import com.app.tracker.workspace.model.WorkspaceRole;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace'in Slack bildirim entegrasyonu. Yalniz workspace ADMIN'i: adres, kanala mesaj yazma
 * yetkisi veren bir kimlik bilgisidir ve sunucuyu o adrese HTTP istegi atmaya yonlendirir. Adres
 * hicbir yanitta donmez.
 */
@RestController
@RequestMapping("/api/v1/integrations/slack")
@Profile("!migrate")
@PreAuthorize("@securityGuard.hasCurrentWorkspaceRole('" + WorkspaceRole.ADMIN + "')")
public class SlackIntegrationController {

  private final SlackIntegrationService service;

  public SlackIntegrationController(SlackIntegrationService service) {
    this.service = service;
  }

  @PutMapping
  public SlackIntegrationResponse upsert(@Valid @RequestBody SlackIntegrationRequest request) {
    return SlackIntegrationResponse.from(service.upsert(request.webhookUrl(), request.enabled()));
  }

  @GetMapping
  public SlackIntegrationResponse get() {
    return SlackIntegrationResponse.from(service.get());
  }

  @DeleteMapping
  public ResponseEntity<Void> delete() {
    service.delete();
    return ResponseEntity.noContent().build();
  }
}
