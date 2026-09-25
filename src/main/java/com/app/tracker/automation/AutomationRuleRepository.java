package com.app.tracker.automation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutomationRuleRepository extends JpaRepository<AutomationRule, UUID> {

  List<AutomationRule> findByProjectId(UUID projectId);

  Optional<AutomationRule> findByProjectIdAndTemplateKey(
      UUID projectId, AutomationTemplateKey templateKey);
}
