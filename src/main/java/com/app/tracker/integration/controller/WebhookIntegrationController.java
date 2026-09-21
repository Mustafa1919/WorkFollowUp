package com.app.tracker.integration.controller;

import com.app.tracker.integration.dto.WebhookIntegrationResponse;
import com.app.tracker.integration.dto.WebhookIntegrationSecretResponse;
import com.app.tracker.integration.service.WebhookIntegrationService;
import com.app.tracker.workspace.model.WorkspaceRole;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook entegrasyonu yonetimi. Yalniz workspace ADMIN'i: entegrasyon, dis bir sistemin bu
 * workspace'te gorev durumu degistirmesine izin veren bir kimlik bilgisidir (MANAGER'a bile
 * acilmadi; gerekirse bilincli olarak genisletilir).
 */
@RestController
@RequestMapping("/api/v1/integrations/webhooks")
@Profile("!migrate")
@PreAuthorize("@securityGuard.hasCurrentWorkspaceRole('" + WorkspaceRole.ADMIN + "')")
public class WebhookIntegrationController {

  private final WebhookIntegrationService service;

  public WebhookIntegrationController(WebhookIntegrationService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<WebhookIntegrationSecretResponse> create() {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(WebhookIntegrationSecretResponse.from(service.createGithubIntegration()));
  }

  @GetMapping
  public List<WebhookIntegrationResponse> list() {
    return service.list().stream().map(WebhookIntegrationResponse::from).toList();
  }

  @PostMapping("/{id}/rotate-secret")
  public WebhookIntegrationSecretResponse rotateSecret(@PathVariable UUID id) {
    return WebhookIntegrationSecretResponse.from(service.rotateSecret(id));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
