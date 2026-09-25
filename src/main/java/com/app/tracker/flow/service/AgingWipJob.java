package com.app.tracker.flow.service;

import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.notification.dto.NotificationResponse;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Dalga 2.1 — saatlik "Tenant-Iterating" job ({@code SprintAnalyticsReconciliationJob}/{@code
 * MeetingReminderJob} ile AYNI desen). Is mantigi bilerek AYRI bir bean'de ({@link
 * AgingWipAlertService}) — bu siniftan cagrilan bir {@code @Transactional} metot self-invocation
 * yuzunden Spring proxy'sini atlardi. STOMP push'u da BURADA yapilir (transaction commit olduktan
 * SONRA), {@code MeetingReminderJob} ile ayni "commit'ten SONRA bildir" garantisi.
 */
@Component
@Profile("!migrate")
public class AgingWipJob {

  private static final String USER_NOTIFICATIONS_DESTINATION = "/queue/notifications";

  private static final Logger log = LoggerFactory.getLogger(AgingWipJob.class);

  private final WorkspaceService workspaceService;
  private final TenantExecutor tenantExecutor;
  private final ProjectRepository projectRepository;
  private final AgingWipAlertService agingWipAlertService;
  private final SimpMessagingTemplate messagingTemplate;
  private final ObjectMapper objectMapper;

  public AgingWipJob(
      WorkspaceService workspaceService,
      TenantExecutor tenantExecutor,
      ProjectRepository projectRepository,
      AgingWipAlertService agingWipAlertService,
      SimpMessagingTemplate messagingTemplate,
      ObjectMapper objectMapper) {
    this.workspaceService = workspaceService;
    this.tenantExecutor = tenantExecutor;
    this.projectRepository = projectRepository;
    this.agingWipAlertService = agingWipAlertService;
    this.messagingTemplate = messagingTemplate;
    this.objectMapper = objectMapper;
  }

  @Scheduled(fixedDelay = 3_600_000)
  public void checkAging() {
    for (UUID workspaceId : workspaceService.findAllWorkspaceIds()) {
      try {
        tenantExecutor.runAs(workspaceId, this::checkWorkspace);
      } catch (RuntimeException e) {
        log.error("Aging WIP kontrolu basarisiz (workspace={}).", workspaceId, e);
      }
    }
  }

  private void checkWorkspace() {
    for (var project : projectRepository.findAll()) {
      List<Notification> created = agingWipAlertService.checkProject(project);
      for (Notification notification : created) {
        messagingTemplate.convertAndSendToUser(
            notification.getUserId().toString(),
            USER_NOTIFICATIONS_DESTINATION,
            objectMapper.writeValueAsString(NotificationResponse.from(notification)));
      }
    }
  }
}
