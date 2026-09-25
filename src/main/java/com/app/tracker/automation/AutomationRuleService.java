package com.app.tracker.automation;

import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sablon tanimlarinin CRUD'u + "bu proje icin sablon acik mi" kapisi ({@link #isEnabled}). Kapi,
 * hem {@code AutomationRuleEngine} (task.events tuketicisi) hem de {@code GithubEventProcessor}
 * (PR_MERGE_TO_DONE) tarafindan cagirilir — tek dogruluk kaynagi.
 */
@Service
public class AutomationRuleService {

  private final AutomationRuleRepository automationRuleRepository;
  private final ProjectRepository projectRepository;

  public AutomationRuleService(
      AutomationRuleRepository automationRuleRepository, ProjectRepository projectRepository) {
    this.automationRuleRepository = automationRuleRepository;
    this.projectRepository = projectRepository;
  }

  /**
   * Varsayilan degeri {@link AutomationTemplateKey} javadoc'unda: yalniz PR_MERGE_TO_DONE acik
   * baslar.
   */
  private static boolean defaultEnabled(AutomationTemplateKey key) {
    return key == AutomationTemplateKey.PR_MERGE_TO_DONE;
  }

  @Transactional(readOnly = true)
  public boolean isEnabled(UUID projectId, AutomationTemplateKey key) {
    return automationRuleRepository
        .findByProjectIdAndTemplateKey(projectId, key)
        .map(AutomationRule::isEnabled)
        .orElseGet(() -> defaultEnabled(key));
  }

  public record TemplateStatus(
      AutomationTemplateKey templateKey, boolean enabled, boolean configured) {}

  /**
   * Sablonlarin TUMU icin (satiri olsun olmasin) mevcut durum — UI'nin ac/kapa listesi bundan
   * cizilir.
   */
  @Transactional(readOnly = true)
  public List<TemplateStatus> statusesForProject(UUID projectId) {
    requireProject(projectId);
    Map<AutomationTemplateKey, AutomationRule> existing =
        new EnumMap<>(AutomationTemplateKey.class);
    for (AutomationRule rule : automationRuleRepository.findByProjectId(projectId)) {
      existing.put(rule.getTemplateKey(), rule);
    }
    List<TemplateStatus> result = new ArrayList<>();
    for (AutomationTemplateKey key : AutomationTemplateKey.values()) {
      AutomationRule rule = existing.get(key);
      boolean enabled = rule != null ? rule.isEnabled() : defaultEnabled(key);
      result.add(new TemplateStatus(key, enabled, rule != null));
    }
    return result;
  }

  @Transactional
  public AutomationRule setEnabled(
      UUID projectId, AutomationTemplateKey key, boolean enabled, UUID actorId) {
    Project project = requireProject(projectId);
    AutomationRule rule =
        automationRuleRepository
            .findByProjectIdAndTemplateKey(projectId, key)
            .orElseGet(
                () ->
                    AutomationRule.of(
                        UUID.randomUUID(), project.getWorkspaceId(), projectId, key, actorId));
    rule.changeEnabled(enabled);
    return automationRuleRepository.save(rule);
  }

  private Project requireProject(UUID projectId) {
    return projectRepository
        .findById(projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Proje bulunamadi."));
  }
}
