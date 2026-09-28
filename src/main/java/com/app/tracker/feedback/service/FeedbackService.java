package com.app.tracker.feedback.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.feedback.model.Feedback;
import com.app.tracker.feedback.repository.FeedbackRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dalga 4 -- senkron, dogrudan DB yazimi (TelemetryEventPublisher'in best-effort Kafka yolunun
 * AKSINE): kullanicinin bilincli doldurdugu bir formun kaybi tolere edilemez.
 */
@Service
public class FeedbackService {

  private final FeedbackRepository feedbackRepository;

  public FeedbackService(FeedbackRepository feedbackRepository) {
    this.feedbackRepository = feedbackRepository;
  }

  @Transactional
  public Feedback submit(UUID userId, String message, String pagePath) {
    UUID workspaceId = requireWorkspace();
    return feedbackRepository.save(
        Feedback.of(UUID.randomUUID(), workspaceId, userId, message, pagePath));
  }

  @Transactional(readOnly = true)
  public List<Feedback> list() {
    return feedbackRepository.findAllByOrderByCreatedAtDesc();
  }

  private static UUID requireWorkspace() {
    UUID workspaceId = TenantContext.getWorkspaceId();
    if (workspaceId == null) {
      throw new BusinessRuleException("Once bir workspace secmelisiniz (X-Workspace-Id header).");
    }
    return workspaceId;
  }
}
