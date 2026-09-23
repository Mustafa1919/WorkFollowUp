package com.app.tracker.notification.service;

import com.app.tracker.core.idempotency.ProcessedEventStore;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.repository.NotificationRepository;
import com.app.tracker.notification.service.NotificationMessageFormatter.TaskSummary;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskRepository;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * In-app Inbox Notification Worker'in is mantigi (PHASE_6_PRODUCT_FEATURES.md Bolum 4, "Fan-out on
 * Write"). CycleTimeProjector ile AYNI desen: idempotency isaretlemesi ve satir yazimi TEK
 * transaction'da (bkz. ProcessedEventStore javadoc'u) — is verisi (bu is'te dis HTTP cagrisi YOK,
 * saf DB yazimi) oldugu icin Slack worker'inin "kisa tx + dis cagri arasi" bolunmesine gerek
 * yoktur.
 *
 * <p><b>Alici tanimi (bilincli tasarim karari):</b> {@code tasks.assignee_id} (V1'den beri var ama
 * hicbir yerde kullanilmayan bir kolon) kasitli olarak KULLANILMADI — bu ozelligin kapsami sadece
 * Inbox'tir, gorev atama Hedefler.md yol haritasinda daha sonraki bir dilim. Bunun yerine alici =
 * "workspace'teki DEVELOPER ve uzeri roller (ADMIN/MANAGER/DEVELOPER), VIEWER HARIC (salt-okunur
 * uye is yapmiyor, bildirim onun icin gurultu), aktor HARIC". Bu, mevcut Slack entegrasyonuyla AYNI
 * "workspace genelinde yayin" felsefesini (tek kanal, tum uyeler) kullanicilar seviyesine tasir.
 * Bilinen sinir: buyuk workspace'lerde her durum degisikligi TUM aktif uyelere duser, gurultu
 * uretebilir; assignee_id koda baglanirsa alici tanimi "yalniz atanan + izleyenler"e daralmali.
 */
@Service
@Profile("!migrate")
public class InboxFanoutService {

  public static final String CONSUMER = "notification-inbox";

  /** VIEWER haric: salt-okunur uye is akisinin bir parcasi degil, bildirim onun icin gurultu. */
  private static final Set<String> RECIPIENT_ROLES =
      Set.of(WorkspaceRole.ADMIN, WorkspaceRole.MANAGER, WorkspaceRole.DEVELOPER);

  private final ProcessedEventStore processedEventStore;
  private final NotificationRepository notificationRepository;
  private final WorkspaceUserRepository workspaceUserRepository;
  private final TaskRepository taskRepository;
  private final ProjectRepository projectRepository;

  public InboxFanoutService(
      ProcessedEventStore processedEventStore,
      NotificationRepository notificationRepository,
      WorkspaceUserRepository workspaceUserRepository,
      TaskRepository taskRepository,
      ProjectRepository projectRepository) {
    this.processedEventStore = processedEventStore;
    this.notificationRepository = notificationRepository;
    this.workspaceUserRepository = workspaceUserRepository;
    this.taskRepository = taskRepository;
    this.projectRepository = projectRepository;
  }

  /**
   * Idempotency isaretlemesi ile satir yazimi AYNI transaction'dadir. Gorev/proje arada silinmisse
   * (RLS'ten gecmiyor veya soft-delete ile filtrelenmisse) ya da olay bildirime konu
   * degilse/gerekli alan eksikse BOS liste doner — bu durumlarda da olay "islendi" sayilir (yeniden
   * denemek anlamsiz, SlackDeliveryStore'un ayni bosluklar icin Optional.empty() ile "atla"
   * davranisiyla tutarli).
   */
  @Transactional
  public List<Notification> fanOut(
      UUID eventId, UUID workspaceId, String eventType, JsonNode payload) {
    if (!processedEventStore.markProcessed(CONSUMER, eventId)) {
      return List.of();
    }
    UUID taskId = uuid(payload, "taskId");
    Optional<Task> maybeTask = taskRepository.findById(taskId);
    if (maybeTask.isEmpty()) {
      return List.of();
    }
    Task task = maybeTask.get();
    Optional<Project> maybeProject = projectRepository.findById(task.getProjectId());
    if (maybeProject.isEmpty()) {
      return List.of();
    }
    TaskSummary summary =
        new TaskSummary(maybeProject.get().getKey(), task.getTaskNumber(), task.getTitle());
    Optional<String[]> message = NotificationMessageFormatter.format(eventType, payload, summary);
    if (message.isEmpty()) {
      return List.of();
    }
    UUID actorId = uuidOrNull(payload, "actorId");
    List<UUID> recipients = resolveRecipients(workspaceId, actorId);
    if (recipients.isEmpty()) {
      return List.of();
    }

    String payloadJson = payload.toString();
    Instant now = Instant.now();
    String title = message.get()[0];
    String body = message.get()[1];
    List<Notification> created = new ArrayList<>();
    for (UUID recipientId : recipients) {
      Notification notification =
          Notification.of(
              UUID.randomUUID(),
              workspaceId,
              recipientId,
              eventType,
              taskId,
              task.getProjectId(),
              title,
              body,
              now);
      notificationRepository.save(notification);
      notificationRepository.writePayload(notification.getId(), payloadJson);
      created.add(notification);
    }
    return created;
  }

  /**
   * {@code actorId} eksikse (eski/replay edilmis bir olay) kimse HARIC TUTULMAZ — bu bilinen, kabul
   * edilebilir bir gerileme (aktor kendi eylemi icin de bildirim gorebilir), yanlis kisiyi haric
   * tutmaktan (veri kaybi) daha guvenli bir varsayilan.
   */
  private List<UUID> resolveRecipients(UUID workspaceId, UUID actorId) {
    return workspaceUserRepository.findByWorkspaceId(workspaceId).stream()
        .filter(member -> RECIPIENT_ROLES.contains(member.getRole()))
        .map(WorkspaceUser::getUserId)
        .filter(userId -> !userId.equals(actorId))
        .distinct()
        .toList();
  }

  private static UUID uuid(JsonNode node, String field) {
    String value = node.path(field).asString(null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Eksik alan: " + field);
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Gecersiz UUID alani: " + field, e);
    }
  }

  private static UUID uuidOrNull(JsonNode node, String field) {
    String value = node.path(field).asString(null);
    if (value == null || value.isBlank()) {
      return null;
    }
    return UUID.fromString(value);
  }
}
