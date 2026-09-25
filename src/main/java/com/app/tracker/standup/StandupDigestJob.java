package com.app.tracker.standup;

import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.meeting.model.Meeting;
import com.app.tracker.meeting.service.MeetingOccurrenceCalculator;
import com.app.tracker.meeting.service.MeetingService;
import com.app.tracker.notification.dto.NotificationResponse;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.service.SlackIntegrationService;
import com.app.tracker.notification.service.SlackPermanentException;
import com.app.tracker.notification.service.SlackSender;
import com.app.tracker.notification.service.SlackTransientException;
import com.app.tracker.workspace.service.WorkspaceService;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Dalga 2.3 — {@code MeetingReminderJob} ile AYNI "Tenant-Iterating" + "her dakika kontrol et"
 * deseni, tek fark tetikleme anı: occurrence baslangicindan TAM 30 DAKIKA once (plan karari, {@code
 * reminderMinutesBefore}'dan BAGIMSIZ — standup, hatirlatma acik olmasa da calisir).
 */
@Component
@Profile("!migrate")
public class StandupDigestJob {

  private static final String USER_NOTIFICATIONS_DESTINATION = "/queue/notifications";
  private static final int MINUTES_BEFORE = 30;

  private static final Logger log = LoggerFactory.getLogger(StandupDigestJob.class);

  private final WorkspaceService workspaceService;
  private final TenantExecutor tenantExecutor;
  private final MeetingService meetingService;
  private final StandupDigestService digestService;
  private final SlackIntegrationService slackIntegrationService;
  private final SlackSender slackSender;
  private final SimpMessagingTemplate messagingTemplate;
  private final ObjectMapper objectMapper;
  private final java.time.Clock clock;

  public StandupDigestJob(
      WorkspaceService workspaceService,
      TenantExecutor tenantExecutor,
      MeetingService meetingService,
      StandupDigestService digestService,
      SlackIntegrationService slackIntegrationService,
      SlackSender slackSender,
      SimpMessagingTemplate messagingTemplate,
      ObjectMapper objectMapper,
      java.time.Clock clock) {
    this.workspaceService = workspaceService;
    this.tenantExecutor = tenantExecutor;
    this.meetingService = meetingService;
    this.digestService = digestService;
    this.slackIntegrationService = slackIntegrationService;
    this.slackSender = slackSender;
    this.messagingTemplate = messagingTemplate;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 60_000)
  public void checkStandups() {
    for (UUID workspaceId : workspaceService.findAllWorkspaceIds()) {
      try {
        tenantExecutor.runAs(workspaceId, this::checkWorkspace);
      } catch (RuntimeException e) {
        log.error("Standup ozeti kontrolu basarisiz (workspace={}).", workspaceId, e);
      }
    }
  }

  private void checkWorkspace() {
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDate today = now.toLocalDate();
    for (Meeting meeting : meetingService.listWithStandupEnabled()) {
      List<LocalDate> todaysOccurrence =
          MeetingOccurrenceCalculator.occurrencesInRange(meeting, today, today);
      if (todaysOccurrence.isEmpty()) {
        continue;
      }
      LocalTime triggerTime = meeting.getStartTime().minusMinutes(MINUTES_BEFORE);
      LocalDateTime triggerAt = today.atTime(triggerTime);
      boolean withinThisMinute = !now.isBefore(triggerAt) && now.isBefore(triggerAt.plusMinutes(1));
      if (withinThisMinute) {
        List<Notification> created = digestService.generate(meeting, today);
        for (Notification notification : created) {
          messagingTemplate.convertAndSendToUser(
              notification.getUserId().toString(),
              USER_NOTIFICATIONS_DESTINATION,
              objectMapper.writeValueAsString(NotificationResponse.from(notification)));
        }
        if (!created.isEmpty()) {
          sendSlackSummary(meeting, created.size());
        }
      }
    }
  }

  private void sendSlackSummary(Meeting meeting, int participantCount) {
    Optional<URI> target = slackIntegrationService.findActiveWebhookUrl();
    if (target.isEmpty()) {
      return;
    }
    String text =
        "Standup özeti hazır: "
            + meeting.getTitle()
            + " ("
            + participantCount
            + " katılımcı) — /standups sayfasında.";
    try {
      slackSender.send(target.get(), text);
    } catch (SlackPermanentException e) {
      log.warn(
          "Standup ozeti Slack tarafindan reddedildi (meeting={}): {}",
          meeting.getId(),
          e.getMessage());
    } catch (SlackTransientException e) {
      log.warn(
          "Standup ozeti Slack'e gonderilemedi (meeting={}), yeniden denenmeyecek.",
          meeting.getId(),
          e);
    }
  }
}
