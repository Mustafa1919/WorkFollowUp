package com.app.tracker.automation.dto;

import com.app.tracker.automation.AutomationRuleService.TemplateStatus;
import com.app.tracker.automation.AutomationTemplateKey;

public record AutomationRuleResponse(
    AutomationTemplateKey templateKey, boolean enabled, boolean configured) {

  public static AutomationRuleResponse from(TemplateStatus status) {
    return new AutomationRuleResponse(status.templateKey(), status.enabled(), status.configured());
  }
}
