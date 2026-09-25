package com.app.tracker.retro;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.retro.RetroItemRepository.Row;
import com.app.tracker.retro.dto.RetroItemResponse;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.repository.SprintRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dalga 2.4 — retro panosu (serbest metin maddeleri). Sahiplik {@code SavedViewService} ile AYNI
 * sikilikta: yalniz YAZAN silebilir, ADMIN istisnasi YOK (kisisel bir gorus notu, is verisi degil).
 */
@Service
public class RetroItemService {

  private static final Set<String> VALID_KINDS =
      Set.of(RetroItemKind.WENT_WELL, RetroItemKind.IMPROVE, RetroItemKind.ACTION);

  private final RetroItemRepository retroItemRepository;
  private final SprintRepository sprintRepository;
  private final TaskService taskService;
  private final UserRepository userRepository;

  public RetroItemService(
      RetroItemRepository retroItemRepository,
      SprintRepository sprintRepository,
      TaskService taskService,
      UserRepository userRepository) {
    this.retroItemRepository = retroItemRepository;
    this.sprintRepository = sprintRepository;
    this.taskService = taskService;
    this.userRepository = userRepository;
  }

  @Transactional
  public RetroItemResponse create(UUID sprintId, String kind, String body, UUID authorId) {
    if (!VALID_KINDS.contains(kind)) {
      throw new BusinessRuleException("Gecersiz madde turu: " + kind);
    }
    Sprint sprint = requireSprint(sprintId);
    UUID id = UUID.randomUUID();
    retroItemRepository.insert(id, sprint.getWorkspaceId(), sprintId, kind, body.trim(), authorId);
    Row row = retroItemRepository.findById(id).orElseThrow();
    String authorName =
        userRepository.findById(authorId).map(User::getFullName).orElse("Bilinmeyen kullanici");
    return new RetroItemResponse(
        row.id(),
        row.kind(),
        row.body(),
        row.authorId(),
        authorName,
        row.taskId(),
        row.createdAt());
  }

  @Transactional(readOnly = true)
  public List<RetroItemResponse> list(UUID sprintId) {
    List<Row> rows = retroItemRepository.findBySprintId(sprintId);
    Map<UUID, String> names = resolveAuthorNames(rows);
    return rows.stream()
        .map(
            row ->
                new RetroItemResponse(
                    row.id(),
                    row.kind(),
                    row.body(),
                    row.authorId(),
                    names.getOrDefault(row.authorId(), "Bilinmeyen kullanici"),
                    row.taskId(),
                    row.createdAt()))
        .toList();
  }

  @Transactional
  public void delete(UUID id, UUID actorId) {
    Row row = requireItem(id);
    if (!row.authorId().equals(actorId)) {
      throw new AccessDeniedException("Yalniz kendi retro maddenizi silebilirsiniz.");
    }
    retroItemRepository.delete(id);
  }

  /**
   * {@code action} turundeki bir maddeyi gorece donusturur; zaten donusturulmusse tekrar YAPMAZ.
   */
  @Transactional
  public Task convertToTask(UUID id, UUID actorId) {
    Row row = requireItem(id);
    if (!RetroItemKind.ACTION.equals(row.kind())) {
      throw new BusinessRuleException(
          "Yalniz 'action' turundeki maddeler goreve donusturulebilir.");
    }
    if (row.taskId() != null) {
      throw new BusinessRuleException("Bu madde zaten bir goreve donusturulmus.");
    }
    Sprint sprint = requireSprint(row.sprintId());
    // Sprint bu noktada ZATEN tamamlanmis (retro yalniz tamamlanmis sprint'ler icin gorulur) —
    // TaskService.assignSprint tamamlanmis bir sprint'e yeni gorev eklenmesini reddeder (V9
    // kurali), bu yuzden yeni gorev BACKLOG'a (sprintsiz) duser, kullanici isterse elle sonraki
    // sprint'e tasir.
    Task task = taskService.createTask(sprint.getProjectId(), row.body(), null, actorId);
    retroItemRepository.attachTask(id, task.getId());
    return task;
  }

  private Row requireItem(UUID id) {
    return retroItemRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Retro maddesi bulunamadi."));
  }

  private Sprint requireSprint(UUID sprintId) {
    return sprintRepository
        .findById(sprintId)
        .orElseThrow(() -> new ResourceNotFoundException("Sprint bulunamadi."));
  }

  private Map<UUID, String> resolveAuthorNames(List<Row> rows) {
    List<UUID> authorIds = rows.stream().map(Row::authorId).distinct().toList();
    if (authorIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> names = new LinkedHashMap<>();
    for (User user : userRepository.findAllById(authorIds)) {
      names.put(user.getId(), user.getFullName());
    }
    return names;
  }
}
