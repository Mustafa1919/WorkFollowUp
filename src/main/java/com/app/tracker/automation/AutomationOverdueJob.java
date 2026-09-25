package com.app.tracker.automation;

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
 * Dalga 3.1 (OVERDUE_NOTIFY) — saatlik "Tenant-Iterating" job, {@code AgingWipJob} ile BIREBIR AYNI
 * desen (gunluk dedup {@link AutomationNotificationLedgerRepository}'de oldugu icin saatlik
 * calismak zararsiz — ayni gun ikinci kosuda hicbir gorev icin tekrar bildirim uretilmez). STOMP
 * push transaction commit olduktan SONRA yapilir.
 */
@Component
@Profile("!migrate")
public class AutomationOverdueJob {

  private static final String USER_NOTIFICATIONS_DESTINATION = "/queue/notifications";

  private static final Logger log = LoggerFactory.getLogger(AutomationOverdueJob.class);

  private final WorkspaceService workspaceService;
  private final TenantExecutor tenantExecutor;
  private final ProjectRepository projectRepository;
  private final AutomationOverdueAlertService automationOverdueAlertService;
  private final SimpMessagingTemplate messagingTemplate;
  private final ObjectMapper objectMapper;

  public AutomationOverdueJob(
      WorkspaceService workspaceService,
      TenantExecutor tenantExecutor,
      ProjectRepository projectRepository,
      AutomationOverdueAlertService automationOverdueAlertService,
      SimpMessagingTemplate messagingTemplate,
      ObjectMapper objectMapper) {
    this.workspaceService = workspaceService;
    this.tenantExecutor = tenantExecutor;
    this.projectRepository = projectRepository;
    this.automationOverdueAlertService = automationOverdueAlertService;
    this.messagingTemplate = messagingTemplate;
    this.objectMapper = objectMapper;
  }

  @Scheduled(fixedDelay = 3_600_000)
  public void checkOverdue() {
    for (UUID workspaceId : workspaceService.findAllWorkspaceIds()) {
      try {
        tenantExecutor.runAs(workspaceId, this::checkWorkspace);
      } catch (RuntimeException e) {
        log.error("Overdue kontrolu basarisiz (workspace={}).", workspaceId, e);
      }
    }
  }

  private void checkWorkspace() {
    for (var project : projectRepository.findAll()) {
      List<Notification> created = automationOverdueAlertService.checkProject(project);
      for (Notification notification : created) {
        messagingTemplate.convertAndSendToUser(
            notification.getUserId().toString(),
            USER_NOTIFICATIONS_DESTINATION,
            objectMapper.writeValueAsString(NotificationResponse.from(notification)));
      }
    }
  }
}
