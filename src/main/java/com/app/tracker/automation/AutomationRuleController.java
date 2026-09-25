package com.app.tracker.automation;

import com.app.tracker.automation.dto.AutomationRuleResponse;
import com.app.tracker.automation.dto.AutomationRuleToggleRequest;
import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.workspace.model.WorkspaceRole;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dalga 3.1 (ADR-0016) — proje bazinda otomasyon sablonu ac/kapa. Okuma herhangi bir uye
 * (sablonlarin ne yaptigi/acik olup olmadigi bilgilendiricidir); yazma ADMIN/MANAGER — {@code
 * TagController}/{@code GoalController} ile AYNI gerekce: bir otomasyonu acmak, sistemin gorevleri
 * kimin adina (otomasyon aktoru) ve ne zaman degistirecegine dair workspace-genelinde etkili bir
 * karardir.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/automation-rules")
public class AutomationRuleController {

  private static final String MANAGE =
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "')";

  private final AutomationRuleService automationRuleService;

  public AutomationRuleController(AutomationRuleService automationRuleService) {
    this.automationRuleService = automationRuleService;
  }

  @GetMapping
  public List<AutomationRuleResponse> list(@PathVariable UUID projectId) {
    return automationRuleService.statusesForProject(projectId).stream()
        .map(AutomationRuleResponse::from)
        .toList();
  }

  @PutMapping("/{templateKey}")
  @PreAuthorize(MANAGE)
  public AutomationRuleResponse setEnabled(
      @PathVariable UUID projectId,
      @PathVariable AutomationTemplateKey templateKey,
      @Valid @RequestBody AutomationRuleToggleRequest request) {
    AutomationRule rule =
        automationRuleService.setEnabled(
            projectId, templateKey, request.enabled(), CurrentUser.id());
    return new AutomationRuleResponse(rule.getTemplateKey(), rule.isEnabled(), true);
  }
}
