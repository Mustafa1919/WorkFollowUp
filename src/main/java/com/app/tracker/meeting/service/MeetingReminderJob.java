package com.app.tracker.meeting.service;

import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.meeting.model.Meeting;
import com.app.tracker.notification.dto.NotificationResponse;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.workspace.service.WorkspaceService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
 * Her dakika çalışıp hatırlatması olan toplantıların bugünkü occurrence'ını kontrol eder.
 * "Tenant-Iterating" deseni ({@code SprintAnalyticsReconciliationJob} birebir): worker'ın elinde
 * JWT yok, her workspace'in tenant bağlamı {@code TenantExecutor} ile programatik kurulur.
 *
 * <p>İşin kendisi (idempotency + Inbox/Slack) kasıtlı olarak AYRI bir bean'de ({@link
 * MeetingReminderService}) — aynı sınıf içinden çağrılan bir {@code @Transactional} metot Spring'in
 * proxy'sini atlar (self-invocation), transaction hiç açılmaz. Bu job sadece orkestre eder, iş
 * mantığı taşımaz; STOMP push'u da BURADA yapılır ({@code reminderService.remind} döndükten sonra —
 * transaction'ı zaten commit olmuştur — {@code InboxNotificationConsumer} ile AYNI "commit'ten
 * SONRA bildir" deseni).
 */
@Component
@Profile("!migrate")
public class MeetingReminderJob {

  private static final String USER_NOTIFICATIONS_DESTINATION = "/queue/notifications";

  private static final Logger log = LoggerFactory.getLogger(MeetingReminderJob.class);

  private final WorkspaceService workspaceService;
  private final TenantExecutor tenantExecutor;
  private final MeetingService meetingService;
  private final MeetingReminderService reminderService;
  private final SimpMessagingTemplate messagingTemplate;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public MeetingReminderJob(
      WorkspaceService workspaceService,
      TenantExecutor tenantExecutor,
      MeetingService meetingService,
      MeetingReminderService reminderService,
      SimpMessagingTemplate messagingTemplate,
      ObjectMapper objectMapper,
      Clock clock) {
    this.workspaceService = workspaceService;
    this.tenantExecutor = tenantExecutor;
    this.meetingService = meetingService;
    this.reminderService = reminderService;
    this.messagingTemplate = messagingTemplate;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 60_000)
  public void checkReminders() {
    for (UUID workspaceId : workspaceService.findAllWorkspaceIds()) {
      try {
        tenantExecutor.runAs(workspaceId, this::checkWorkspace);
      } catch (RuntimeException e) {
        log.error("Toplantı hatırlatması kontrolü başarısız (workspace={}).", workspaceId, e);
      }
    }
  }

  private void checkWorkspace() {
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDate today = now.toLocalDate();
    for (Meeting meeting : meetingService.listWithReminders()) {
      List<LocalDate> todaysOccurrence =
          MeetingOccurrenceCalculator.occurrencesInRange(meeting, today, today);
      if (todaysOccurrence.isEmpty()) {
        continue;
      }
      LocalTime triggerTime =
          meeting.getStartTime().minusMinutes(meeting.getReminderMinutesBefore());
      LocalDateTime triggerAt = today.atTime(triggerTime);
      boolean withinThisMinute = !now.isBefore(triggerAt) && now.isBefore(triggerAt.plusMinutes(1));
      if (withinThisMinute) {
        for (Notification notification : reminderService.remind(meeting, today)) {
          messagingTemplate.convertAndSendToUser(
              notification.getUserId().toString(),
              USER_NOTIFICATIONS_DESTINATION,
              objectMapper.writeValueAsString(NotificationResponse.from(notification)));
        }
      }
    }
  }
}
