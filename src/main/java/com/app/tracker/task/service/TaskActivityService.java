package com.app.tracker.task.service;

import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.task.dto.TaskActivityResponse;
import com.app.tracker.task.repository.TaskEventRepository;
import com.app.tracker.task.repository.TaskEventRepository.ActivityEntry;
import com.app.tracker.task.repository.TaskRepository;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dalga 1.5 — Activity sekmesi: {@code task_events}'i (en yeni once, keyset sayfali) okur ve aktor
 * adlarini TEK sorguda toplu cozer (CommentPanel'in aksine, burada bunu istemciye birakmiyoruz —
 * bir aktor artik workspace uyesi olmayabilir ya da webhook sistem aktoru (V13) olabilir, ikisi de
 * mevcut {@code useMemberMap()} istemci onbelleginde YOKTUR; {@code users} workspace-bagimsiz ve
 * RLS'siz oldugundan id ile dogrudan bulunabilir).
 */
@Service
public class TaskActivityService {

  private static final int DEFAULT_LIMIT = 30;

  private final TaskEventRepository taskEventRepository;
  private final TaskRepository taskRepository;
  private final UserRepository userRepository;

  public TaskActivityService(
      TaskEventRepository taskEventRepository,
      TaskRepository taskRepository,
      UserRepository userRepository) {
    this.taskEventRepository = taskEventRepository;
    this.taskRepository = taskRepository;
    this.userRepository = userRepository;
  }

  @Transactional(readOnly = true)
  public PageResponse<TaskActivityResponse> listActivity(UUID taskId, int limit, String cursor) {
    requireTask(taskId);
    int boundedLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
    List<ActivityEntry> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = taskEventRepository.findFirstPage(taskId, boundedLimit + 1);
    } else {
      TaskActivityCursor decoded = TaskActivityCursor.decode(cursor);
      rows =
          taskEventRepository.findNextPage(
              taskId, decoded.createdAt(), decoded.id(), boundedLimit + 1);
    }
    boolean hasMore = rows.size() > boundedLimit;
    List<ActivityEntry> page = hasMore ? rows.subList(0, boundedLimit) : rows;
    String nextCursor = null;
    if (hasMore) {
      ActivityEntry last = page.get(page.size() - 1);
      nextCursor = new TaskActivityCursor(last.createdAt(), last.id()).encode();
    }
    return new PageResponse<>(toResponses(page), nextCursor, hasMore);
  }

  private List<TaskActivityResponse> toResponses(List<ActivityEntry> entries) {
    Map<UUID, String> actorNames = resolveActorNames(entries);
    return entries.stream()
        .map(
            e ->
                new TaskActivityResponse(
                    e.id(),
                    e.actorId(),
                    actorNames.getOrDefault(e.actorId(), "Bilinmeyen kullanici"),
                    e.eventType(),
                    e.field(),
                    e.oldValue(),
                    e.newValue(),
                    e.createdAt()))
        .toList();
  }

  private Map<UUID, String> resolveActorNames(List<ActivityEntry> entries) {
    List<UUID> actorIds = entries.stream().map(ActivityEntry::actorId).distinct().toList();
    if (actorIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> names = new LinkedHashMap<>();
    for (User user : userRepository.findAllById(actorIds)) {
      names.put(user.getId(), user.getFullName());
    }
    return names;
  }

  /** TaskService#requireTask ile AYNI: silinmis gorevlerin aktivitesi okunamaz. */
  private void requireTask(UUID taskId) {
    taskRepository
        .findById(taskId)
        .filter(task -> !task.isDeleted())
        .orElseThrow(() -> new ResourceNotFoundException("Gorev bulunamadi."));
  }
}
