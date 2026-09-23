package com.app.tracker.meeting.service;

import com.app.tracker.core.idempotency.ProcessedEventStore;
import com.app.tracker.meeting.model.Meeting;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.repository.NotificationRepository;
import com.app.tracker.notification.service.SlackIntegrationService;
import com.app.tracker.notification.service.SlackPermanentException;
import com.app.tracker.notification.service.SlackSender;
import com.app.tracker.notification.service.SlackTransientException;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bir toplantı occurrence'ının hatırlatmasını Inbox + Slack'e gönderir. {@link
 * com.app.tracker.meeting.service.MeetingReminderJob}'dan (ayrı bean — self-invocation'da
 * {@code @Transactional} proxy'sinin atlanmaması için, bkz. o sınıfın javadoc'u) çağrılır.
 *
 * <p>{@code InboxFanoutService}/{@code SlackNotificationService}'i ÇAĞIRMAZ: ikisi de {@code
 * task.events} payload'ına (taskId zorunlu) sıkı bağlı; toplantı hatırlatmasının task'ı yok. Alıcı
 * kuralı AYNI (ADMIN/MANAGER/DEVELOPER, VIEWER hariç — davetli kavramı v1'de yok).
 */
@Service
@Profile("!migrate")
public class MeetingReminderService {

  private static final Logger log = LoggerFactory.getLogger(MeetingReminderService.class);
  private static final String CONSUMER = "meeting-reminder";
  private static final Set<String> RECIPIENT_ROLES =
      Set.of(WorkspaceRole.ADMIN, WorkspaceRole.MANAGER, WorkspaceRole.DEVELOPER);
  private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

  private final ProcessedEventStore processedEventStore;
  private final NotificationRepository notificationRepository;
  private final WorkspaceUserRepository workspaceUserRepository;
  private final SlackIntegrationService slackIntegrationService;
  private final SlackSender slackSender;

  public MeetingReminderService(
      ProcessedEventStore processedEventStore,
      NotificationRepository notificationRepository,
      WorkspaceUserRepository workspaceUserRepository,
      SlackIntegrationService slackIntegrationService,
      SlackSender slackSender) {
    this.processedEventStore = processedEventStore;
    this.notificationRepository = notificationRepository;
    this.workspaceUserRepository = workspaceUserRepository;
    this.slackIntegrationService = slackIntegrationService;
    this.slackSender = slackSender;
  }

  /**
   * Bu occurrence için hatırlatma daha önce gönderilmediyse Inbox'a yazar ve (yapılandırılmışsa)
   * Slack'e gönderir. İdempotency tek bir {@code processed_events} kaydıyla sağlanır — Inbox yazımı
   * ve Slack gönderimi birlikte "tek deneme, en iyi çaba" kabul edilir (Kafka'nın ayrı retry/DLT
   * altyapısı bu zamanlanmış iş için yok; SlackTransientException bile yutulur, bir sonraki dakika
   * bu occurrence ZATEN işlenmiş sayılacağı için doğal olarak tekrar denenmez).
   *
   * <p>Döndürülen liste, {@link MeetingReminderJob} tarafından bu metot (ve dolayısıyla
   * transaction'ı) DÖNDÜKTEN SONRA STOMP push'u için kullanılır — {@code InboxNotificationConsumer}
   * ile AYNI "commit'ten SONRA bildir" garantisi (push burada YAPILMAZ, çünkü hâlâ transaction
   * içindeyiz).
   */
  @Transactional
  public List<Notification> remind(Meeting meeting, LocalDate occurrenceDate) {
    UUID eventId = reminderEventId(meeting.getId(), occurrenceDate);
    if (!processedEventStore.markProcessed(CONSUMER, eventId)) {
      return List.of();
    }
    String title = "Toplantı hatırlatması: " + meeting.getTitle();
    String body =
        meeting.getStartTime().format(TIME_FORMAT)
            + " itibarıyla başlıyor"
            + (meeting.getMeetingUrl() != null ? " — " + meeting.getMeetingUrl() : "");

    List<UUID> recipients = resolveRecipients(meeting.getWorkspaceId());
    Instant now = Instant.now();
    List<Notification> created = new ArrayList<>();
    for (UUID recipientId : recipients) {
      Notification notification =
          Notification.of(
              UUID.randomUUID(),
              meeting.getWorkspaceId(),
              recipientId,
              "MEETING_REMINDER",
              null,
              null,
              title,
              body,
              now);
      notificationRepository.save(notification);
      created.add(notification);
    }
    sendSlack(meeting, title, body);
    return created;
  }

  private void sendSlack(Meeting meeting, String title, String body) {
    Optional<URI> target = slackIntegrationService.findActiveWebhookUrl();
    if (target.isEmpty()) {
      return;
    }
    try {
      slackSender.send(target.get(), title + "\n" + body);
    } catch (SlackPermanentException e) {
      log.warn(
          "Toplantı hatırlatması Slack tarafından reddedildi (meeting={}): {}",
          meeting.getId(),
          e.getMessage());
    } catch (SlackTransientException e) {
      log.warn(
          "Toplantı hatırlatması Slack'e gönderilemedi (meeting={}), yeniden denenmeyecek "
              + "(occurrence zaten işlenmiş sayılıyor).",
          meeting.getId(),
          e);
    }
  }

  private List<UUID> resolveRecipients(UUID workspaceId) {
    return workspaceUserRepository.findByWorkspaceId(workspaceId).stream()
        .filter(member -> RECIPIENT_ROLES.contains(member.getRole()))
        .map(WorkspaceUser::getUserId)
        .distinct()
        .toList();
  }

  static UUID reminderEventId(UUID meetingId, LocalDate occurrenceDate) {
    return UUID.nameUUIDFromBytes(
        (meetingId + ":" + occurrenceDate).getBytes(StandardCharsets.UTF_8));
  }
}
