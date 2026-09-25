package com.app.tracker.automation;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * V31__automation_rules.sql — bir proje icin tek bir sablonun ac/kapa durumu. RLS'e tabidir. {@code
 * (projectId, templateKey)} essizdir: bir proje, bir sablonu en fazla bir kez yapilandirir.
 */
@Entity
@Table(name = "automation_rules")
@Getter
@NoArgsConstructor
public class AutomationRule {

  @Id private UUID id;

  private UUID workspaceId;

  private UUID projectId;

  @Enumerated(EnumType.STRING)
  private AutomationTemplateKey templateKey;

  private boolean enabled;

  private String params;

  private UUID createdBy;

  private Instant createdAt;

  private Instant updatedAt;

  public static AutomationRule of(
      UUID id,
      UUID workspaceId,
      UUID projectId,
      AutomationTemplateKey templateKey,
      UUID createdBy) {
    AutomationRule rule = new AutomationRule();
    rule.id = id;
    rule.workspaceId = workspaceId;
    rule.projectId = projectId;
    rule.templateKey = templateKey;
    rule.enabled = true;
    rule.params = "{}";
    rule.createdBy = createdBy;
    rule.createdAt = Instant.now();
    rule.updatedAt = rule.createdAt;
    return rule;
  }

  public void changeEnabled(boolean enabled) {
    this.enabled = enabled;
    this.updatedAt = Instant.now();
  }
}
