package com.app.tracker.comment.service;

import com.app.tracker.comment.model.Comment;
import com.app.tracker.comment.repository.CommentRepository;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.outbox.OutboxEventRepository;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskEventRepository;
import com.app.tracker.task.repository.TaskRepository;
import com.app.tracker.task.repository.TaskWatcherRepository;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Urunlestirme Dalga 1.2 — yorumlar + {@code @[userId]} mention. TaskService/TagService ile AYNI
 * iskelet: outbox uzerinden {@code task.events}'e yayin, izleyici otomasyonu TaskWatcherRepository
 * ile paylasilir (V22'nin izleyici modeli buraya da uygulanir — ADR-0009).
 *
 * <p><b>Iki ayri outbox olayi:</b> {@code COMMENT_ADDED} (her yorumda, {@code mentionedUserIds}
 * alani cift bildirimi onlemek icin tasinir ama InboxFanoutService'in KENDISI mention'lari
 * COMMENT_ADDED alicilarindan CIKARIR) ve {@code COMMENT_MENTION} (yalniz gecerli mention varsa,
 * mentionedUserIds ile). Bu ayrim, ayni yoruma iki kez bildirim gitmesini InboxFanoutService'te
 * (event-bazli, siraya bagimsiz) engeller — bkz. o sinifin javadoc'u.
 *
 * <p>{@code COMMENT_UPDATED} / {@code COMMENT_DELETED} yalniz canli yenileme icindir (diger
 * sekmelerde yorum listesi/sayaci): Inbox/Slack/e-posta tuketicileri bu tipleri bildirim saymaz.
 * Yazan/ADMIN yetki reddi {@link AccessDeniedException} (403) — kural ihlali (400) degil.
 */
@Service
public class CommentService {

  static final int MAX_BODY_LENGTH = 10000;
  private static final String TASK_EVENTS_TOPIC = "task.events";

  private final CommentRepository commentRepository;
  private final TaskRepository taskRepository;
  private final TaskEventRepository taskEventRepository;
  private final TaskWatcherRepository taskWatcherRepository;
  private final WorkspaceUserRepository workspaceUserRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public CommentService(
      CommentRepository commentRepository,
      TaskRepository taskRepository,
      TaskEventRepository taskEventRepository,
      TaskWatcherRepository taskWatcherRepository,
      WorkspaceUserRepository workspaceUserRepository,
      OutboxEventRepository outboxEventRepository,
      ObjectMapper objectMapper,
      Clock clock) {
    this.commentRepository = commentRepository;
    this.taskRepository = taskRepository;
    this.taskEventRepository = taskEventRepository;
    this.taskWatcherRepository = taskWatcherRepository;
    this.workspaceUserRepository = workspaceUserRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<Comment> listComments(UUID taskId, int limit, String cursor) {
    requireTask(taskId);
    Pageable pageable = PageRequest.of(0, limit + 1);
    List<Comment> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = commentRepository.findFirstPage(taskId, pageable);
    } else {
      CommentCursor decoded = CommentCursor.decode(cursor);
      rows = commentRepository.findNextPage(taskId, decoded.createdAt(), decoded.id(), pageable);
    }
    boolean hasMore = rows.size() > limit;
    List<Comment> page = hasMore ? rows.subList(0, limit) : rows;
    String nextCursor = null;
    if (hasMore) {
      Comment last = page.get(page.size() - 1);
      nextCursor = new CommentCursor(last.getCreatedAt(), last.getId()).encode();
    }
    return new PageResponse<>(page, nextCursor, hasMore);
  }

  /**
   * Yorumu ekler; yazan otomatik izleyici olur. Gecerli mention'lar da izleyici olur ve ayrica
   * {@code COMMENT_MENTION} olayiyla (izliyor olsun olmasin) bildirilir.
   */
  @Transactional
  public Comment addComment(UUID taskId, String body, UUID actorId) {
    Task task = requireTask(taskId);
    String normalized = normalize(body);
    Instant now = clock.instant();
    Comment saved =
        commentRepository.save(
            Comment.of(UUID.randomUUID(), task.getWorkspaceId(), taskId, actorId, normalized, now));
    taskEventRepository.recordCommentAdded(taskId, actorId, saved.getId());
    taskWatcherRepository.watch(taskId, actorId, task.getWorkspaceId());

    List<UUID> mentioned = resolveMentions(task.getWorkspaceId(), normalized, actorId);
    for (UUID userId : mentioned) {
      taskWatcherRepository.watch(taskId, userId, task.getWorkspaceId());
    }
    writeCommentEvent("COMMENT_ADDED", task, saved, actorId, mentioned);
    if (!mentioned.isEmpty()) {
      writeCommentEvent("COMMENT_MENTION", task, saved, actorId, mentioned);
    }
    return saved;
  }

  /**
   * Yalniz yazan duzenleyebilir. Yalniz YENI eklenen mention'lar icin {@code COMMENT_MENTION}
   * yazilir (var olan bir mention'i tekrar bildirmek gurultudur) — eski govde yeniden ayristirilip
   * karsilastirilir, mention'lar ayri bir kolonda SAKLANMAZ.
   */
  @Transactional
  public Comment editComment(UUID commentId, String body, UUID actorId) {
    Comment comment = requireComment(commentId);
    Task task = requireTask(comment.getTaskId());
    if (!comment.getAuthorId().equals(actorId)) {
      throw new AccessDeniedException("Yalniz yazan kendi yorumunu duzenleyebilir.");
    }
    if (comment.isDeleted()) {
      throw new BusinessRuleException("Silinmis yorum duzenlenemez.");
    }
    String normalized = normalize(body);
    List<UUID> oldMentions = resolveMentions(task.getWorkspaceId(), comment.getBody(), actorId);
    comment.editBody(normalized, clock.instant());
    Comment saved = commentRepository.save(comment);

    List<UUID> newMentions = resolveMentions(task.getWorkspaceId(), normalized, actorId);
    List<UUID> added = newMentions.stream().filter(id -> !oldMentions.contains(id)).toList();
    for (UUID userId : added) {
      taskWatcherRepository.watch(comment.getTaskId(), userId, task.getWorkspaceId());
    }
    writeCommentEvent("COMMENT_UPDATED", task, saved, actorId, List.of());
    if (!added.isEmpty()) {
      writeCommentEvent("COMMENT_MENTION", task, saved, actorId, added);
    }
    return saved;
  }

  /**
   * Yazan VEYA workspace ADMIN silebilir; govde DB'de kalir, API katmani "[silindi]" gosterir (bkz.
   * Comment javadoc'u). Idempotent: zaten silinmisse no-op.
   */
  @Transactional
  public void deleteComment(UUID commentId, UUID actorId) {
    Comment comment = requireComment(commentId);
    Task task = requireTask(comment.getTaskId());
    if (comment.isDeleted()) {
      return;
    }
    if (!comment.getAuthorId().equals(actorId)
        && !isWorkspaceAdmin(comment.getWorkspaceId(), actorId)) {
      throw new AccessDeniedException("Yalniz yazan veya workspace ADMIN yorumu silebilir.");
    }
    comment.softDelete(clock.instant());
    Comment saved = commentRepository.save(comment);
    writeCommentEvent("COMMENT_DELETED", task, saved, actorId, List.of());
  }

  /** TaskController#toResponses ile AYNI batch desen — liste uc noktalarinda N+1 onlenir. */
  @Transactional(readOnly = true)
  public Map<UUID, Integer> commentCounts(List<UUID> taskIds) {
    if (taskIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Integer> result = new LinkedHashMap<>();
    for (Object[] row : commentRepository.countByTaskIds(taskIds)) {
      result.put((UUID) row[0], ((Long) row[1]).intValue());
    }
    return result;
  }

  /**
   * Ham token'lari (MentionParser) workspace uye listesine karsi dogrular; aktoru ve uye
   * OLMAYANLARI eler, tekillestirir. {@code workspace_users} RLS'siz oldugundan workspaceId acikca
   * verilir (TaskService#assign ile ayni gerekce).
   */
  private List<UUID> resolveMentions(UUID workspaceId, String body, UUID actorId) {
    List<UUID> raw = MentionParser.extract(body);
    if (raw.isEmpty()) {
      return List.of();
    }
    Set<UUID> members =
        workspaceUserRepository.findByWorkspaceId(workspaceId).stream()
            .map(WorkspaceUser::getUserId)
            .collect(Collectors.toSet());
    return raw.stream()
        .filter(members::contains)
        .filter(id -> !id.equals(actorId))
        .distinct()
        .toList();
  }

  private boolean isWorkspaceAdmin(UUID workspaceId, UUID userId) {
    return workspaceUserRepository
        .findByWorkspaceIdAndUserId(workspaceId, userId)
        .map(WorkspaceUser::getRole)
        .map(WorkspaceRole.ADMIN::equals)
        .orElse(false);
  }

  private void writeCommentEvent(
      String eventType, Task task, Comment comment, UUID actorId, List<UUID> mentionedUserIds) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", task.getProjectId().toString());
    payload.put("actorId", actorId == null ? null : actorId.toString());
    payload.put("commentId", comment.getId().toString());
    payload.put("mentionedUserIds", mentionedUserIds.stream().map(UUID::toString).toList());
    outboxEventRepository.write(
        TASK_EVENTS_TOPIC,
        eventType,
        task.getId(),
        task.getWorkspaceId(),
        objectMapper.writeValueAsString(payload));
  }

  private static String normalize(String body) {
    if (body == null) {
      throw new BusinessRuleException("Yorum govdesi bos olamaz.");
    }
    String trimmed = body.trim();
    if (trimmed.isEmpty()) {
      throw new BusinessRuleException("Yorum govdesi bos olamaz.");
    }
    if (trimmed.length() > MAX_BODY_LENGTH) {
      throw new BusinessRuleException("Yorum en fazla " + MAX_BODY_LENGTH + " karakter olabilir.");
    }
    return trimmed;
  }

  private Comment requireComment(UUID commentId) {
    return commentRepository
        .findById(commentId)
        .orElseThrow(() -> new ResourceNotFoundException("Yorum bulunamadi."));
  }

  /** TaskService#requireTask ile AYNI: silinmis gorevlere yorum yazilamaz/okunamaz. */
  private Task requireTask(UUID taskId) {
    return taskRepository
        .findById(taskId)
        .filter(task -> !task.isDeleted())
        .orElseThrow(() -> new ResourceNotFoundException("Gorev bulunamadi."));
  }
}
