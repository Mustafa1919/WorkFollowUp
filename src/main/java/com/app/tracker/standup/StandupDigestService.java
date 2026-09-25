package com.app.tracker.standup;

import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.meeting.model.Meeting;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.repository.NotificationRepository;
import com.app.tracker.standup.dto.StandupDigestResponse;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Dalga 2.3 — bir toplantı occurrence'ı için standup özetlerini üretir. {@link
 * StandupDigestJob}'dan (ayrı bean — self-invocation'da {@code @Transactional} proxy'sinin
 * atlanmaması için, {@code MeetingReminderJob}/{@code MeetingReminderService} ile AYNI ayrıştırma)
 * çağrılır.
 *
 * <p>Alıcı kuralı {@code MeetingReminderService} ile AYNI (ADMIN/MANAGER/DEVELOPER, VIEWER hariç —
 * davetli kavramı v1'de yok). İdempotency {@code standup_digests}'in kendi PRIMARY KEY'i + {@code
 * ON CONFLICT DO NOTHING} ile sağlanır ({@link StandupDigestRepository#insertIfAbsent}).
 */
@Service
@Profile("!migrate")
public class StandupDigestService {

  private static final Set<String> RECIPIENT_ROLES =
      Set.of(WorkspaceRole.ADMIN, WorkspaceRole.MANAGER, WorkspaceRole.DEVELOPER);

  private final WorkspaceUserRepository workspaceUserRepository;
  private final StandupFactsRepository factsRepository;
  private final StandupDigestRepository digestRepository;
  private final NotificationRepository notificationRepository;
  private final UserRepository userRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public StandupDigestService(
      WorkspaceUserRepository workspaceUserRepository,
      StandupFactsRepository factsRepository,
      StandupDigestRepository digestRepository,
      NotificationRepository notificationRepository,
      UserRepository userRepository,
      ObjectMapper objectMapper,
      Clock clock) {
    this.workspaceUserRepository = workspaceUserRepository;
    this.factsRepository = factsRepository;
    this.digestRepository = digestRepository;
    this.notificationRepository = notificationRepository;
    this.userRepository = userRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * @return bu occurrence'ta YENI olusturulan bildirimler (STOMP push'u {@link StandupDigestJob}
   *     tarafindan bu metot dondukten SONRA yapilir — commit'ten SONRA bildir deseni).
   */
  @Transactional
  public List<Notification> generate(Meeting meeting, LocalDate occurrenceDate) {
    ZoneId zone = clock.getZone();
    LocalDate previousBusinessDay = previousBusinessDay(occurrenceDate);
    Instant fromInclusive = previousBusinessDay.atStartOfDay(zone).toInstant();
    Instant toExclusive = previousBusinessDay.plusDays(1).atStartOfDay(zone).toInstant();

    List<Notification> created = new ArrayList<>();
    for (UUID userId : recipients(meeting.getWorkspaceId())) {
      StandupFacts facts = buildFacts(userId, fromInclusive, toExclusive);
      String factsJson = objectMapper.writeValueAsString(facts);
      boolean inserted =
          digestRepository.insertIfAbsent(
              meeting.getWorkspaceId(), meeting.getId(), occurrenceDate, userId, factsJson);
      if (inserted) {
        created.add(notify(meeting, occurrenceDate, userId));
      }
    }
    return created;
  }

  @Transactional
  public void updateNote(UUID meetingId, LocalDate occurrenceDate, UUID userId, String note) {
    boolean updated =
        digestRepository.updateNote(meetingId, occurrenceDate, userId, normalize(note));
    if (!updated) {
      throw new ResourceNotFoundException("Bu occurrence icin standup ozetiniz bulunamadi.");
    }
  }

  /**
   * {@code /standups} sayfasi icin salt okuma; sahiplik kontrolu YOK (takim ozetini herkes okur).
   */
  @Transactional(readOnly = true)
  public List<StandupDigestResponse> list(UUID meetingId, LocalDate occurrenceDate) {
    List<StandupDigestRepository.DigestRow> rows =
        digestRepository.findByMeetingAndDate(meetingId, occurrenceDate);
    Map<UUID, String> names = resolveUserNames(rows);
    return rows.stream()
        .map(
            row ->
                new StandupDigestResponse(
                    row.userId(),
                    names.getOrDefault(row.userId(), "Bilinmeyen kullanici"),
                    objectMapper.readValue(row.factsJson(), StandupFacts.class),
                    row.note(),
                    row.createdAt()))
        .toList();
  }

  private Map<UUID, String> resolveUserNames(List<StandupDigestRepository.DigestRow> rows) {
    List<UUID> userIds =
        rows.stream().map(StandupDigestRepository.DigestRow::userId).distinct().toList();
    if (userIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> names = new LinkedHashMap<>();
    for (User user : userRepository.findAllById(userIds)) {
      names.put(user.getId(), user.getFullName());
    }
    return names;
  }

  private StandupFacts buildFacts(UUID userId, Instant fromInclusive, Instant toExclusive) {
    return new StandupFacts(
        factsRepository.completedInRange(userId, fromInclusive, toExclusive),
        factsRepository.progressedInRange(userId, fromInclusive, toExclusive),
        factsRepository.currentlyInProgress(userId),
        factsRepository.blockedByOpenDependency(userId),
        factsRepository.aging(userId),
        factsRepository.githubActivityInRange(userId, fromInclusive, toExclusive));
  }

  private Notification notify(Meeting meeting, LocalDate occurrenceDate, UUID userId) {
    Notification notification =
        Notification.of(
            UUID.randomUUID(),
            meeting.getWorkspaceId(),
            userId,
            "STANDUP_DIGEST_READY",
            null,
            null,
            "Standup özetin hazır: " + meeting.getTitle(),
            occurrenceDate + " için günlük özetin /standups sayfasında.",
            Instant.now());
    notificationRepository.save(notification);
    return notification;
  }

  private List<UUID> recipients(UUID workspaceId) {
    return workspaceUserRepository.findByWorkspaceId(workspaceId).stream()
        .filter(member -> RECIPIENT_ROLES.contains(member.getRole()))
        .map(WorkspaceUser::getUserId)
        .distinct()
        .toList();
  }

  private static String normalize(String note) {
    return note == null || note.isBlank() ? null : note;
  }

  static LocalDate previousBusinessDay(LocalDate date) {
    LocalDate day = date.minusDays(1);
    while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
      day = day.minusDays(1);
    }
    return day;
  }
}
