package com.app.tracker.tag.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.outbox.OutboxEventRepository;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.tag.dto.TagResponse;
import com.app.tracker.tag.model.Tag;
import com.app.tracker.tag.repository.TagRepository;
import com.app.tracker.tag.repository.TaskTagRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskEventRepository;
import com.app.tracker.task.repository.TaskRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * RAKIP_ANALIZI.md Bolum 3 — Tags/Labels (Faz 6'nin acik noktasiydi, bkz. Hedefler.md). Workspace
 * duzeyinde CRUD + gorev atama/kaldirma. task/repository'ye (TaskRepository) dogrudan bagimlilik,
 * notification/integration paketlerinin zaten yaptigi ile AYNI desendir (GithubEventProcessor,
 * SlackDeliveryStore) — bu projede feature paketleri arasi boyle bir bagimlilik zaten yerlesik.
 */
@Service
public class TagService {

  private static final String TASK_EVENTS_TOPIC = "task.events";

  private final TagRepository tagRepository;
  private final TaskTagRepository taskTagRepository;
  private final TaskRepository taskRepository;
  private final TaskEventRepository taskEventRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  public TagService(
      TagRepository tagRepository,
      TaskTagRepository taskTagRepository,
      TaskRepository taskRepository,
      TaskEventRepository taskEventRepository,
      OutboxEventRepository outboxEventRepository,
      ObjectMapper objectMapper) {
    this.tagRepository = tagRepository;
    this.taskTagRepository = taskTagRepository;
    this.taskRepository = taskRepository;
    this.taskEventRepository = taskEventRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public Tag createTag(String name, String color) {
    UUID workspaceId = requireWorkspace();
    String trimmed = normalize(name);
    rejectDuplicateName(workspaceId, trimmed, null);
    try {
      // saveAndFlush (save DEGIL): iki eszamanli istek ayni ismi ayni anda kontrol edip ikisi de
      // "yok" gorebilir (check-then-act race, bkz. SprintService#startSprint AYNI desen).
      // Flush olmadan INSERT transaction commit'ine kadar ertelenir ve asagidaki catch hic
      // yakalamaz; uq_tags_workspace_lower_name'in ikinci savunma hatti olmasi icin flush sart.
      return tagRepository.saveAndFlush(Tag.of(UUID.randomUUID(), workspaceId, trimmed, color));
    } catch (DataIntegrityViolationException e) {
      throw duplicateNameException();
    }
  }

  @Transactional(readOnly = true)
  public List<Tag> listTags() {
    return tagRepository.findAllByOrderByNameAsc();
  }

  @Transactional
  public Tag update(UUID tagId, String name, String color) {
    Tag tag = requireTag(tagId);
    String trimmed = normalize(name);
    rejectDuplicateName(requireWorkspace(), trimmed, tag);
    tag.rename(trimmed, color);
    try {
      return tagRepository.saveAndFlush(tag);
    } catch (DataIntegrityViolationException e) {
      throw duplicateNameException();
    }
  }

  /** {@code task_tags} satirlari DB'de {@code ON DELETE CASCADE} ile otomatik silinir (V17). */
  @Transactional
  public void deleteTag(UUID tagId) {
    tagRepository.delete(requireTag(tagId));
  }

  /**
   * Etiketi goreve atar (idempotent — zaten atanmissa no-op, outbox'a/tarihceye yazilmaz). Analitik
   * worker'lar (Cycle Time/Velocity/Throughput, PHASE_3_DETAILED_DESIGN.md) yalniz {@code
   * status_changed}/{@code sprint_changed}/{@code story_point_changed}/{@code due_date_changed}
   * okur, etiket degisikligi bu hesaplarin hicbirini etkilemez — {@code task_events} yazimi (Dalga
   * 1.5) yalniz Activity sekmesi icindir. Outbox'a (WebSocket fan-out + Inbox icin) {@code
   * TASK_TAGS_CHANGED} yazilir. Onaylanmis gorev TaskService'teki diger tum alanlar (durum/tarih)
   * gibi kilitlidir — etiket de istisna degil.
   */
  @Transactional
  public Task assign(UUID taskId, UUID tagId, UUID actorId) {
    Task task = requireTask(taskId);
    rejectIfApproved(task);
    Tag tag = requireTag(tagId);
    if (taskTagRepository.assign(taskId, tagId, task.getWorkspaceId())) {
      taskEventRepository.recordTagsChanged(taskId, actorId, tag.getName(), true);
      writeTaskTagsChanged(task, tag, "added");
    }
    return task;
  }

  /** Zaten atanmamissa no-op (outbox'a/tarihceye yazilmaz) — idempotent unassign. */
  @Transactional
  public Task unassign(UUID taskId, UUID tagId, UUID actorId) {
    Task task = requireTask(taskId);
    rejectIfApproved(task);
    Tag tag = requireTag(tagId);
    if (taskTagRepository.unassign(taskId, tagId)) {
      taskEventRepository.recordTagsChanged(taskId, actorId, tag.getName(), false);
      writeTaskTagsChanged(task, tag, "removed");
    }
    return task;
  }

  /** TaskService'teki updateStatus/updateDueDate ile AYNI kilit kurali: onaylanmis gorev donuk. */
  private static void rejectIfApproved(Task task) {
    if (task.isApproved()) {
      throw new BusinessRuleException("Onaylanmis gorevin etiketleri degistirilemez.");
    }
  }

  @Transactional(readOnly = true)
  public List<TagResponse> tagsForTask(UUID taskId) {
    return tagsForTasks(List.of(taskId)).getOrDefault(taskId, List.of());
  }

  /** Liste endpoint'lerinde (list/calendar/approved) N+1'i onlemek icin TEK sorguda batch. */
  @Transactional(readOnly = true)
  public Map<UUID, List<TagResponse>> tagsForTasks(List<UUID> taskIds) {
    Map<UUID, List<TagResponse>> byTask = new LinkedHashMap<>();
    for (TaskTagRepository.TagRow row : taskTagRepository.findTagsForTasks(taskIds)) {
      byTask
          .computeIfAbsent(row.taskId(), k -> new ArrayList<>())
          .add(new TagResponse(row.tagId(), row.name(), row.color(), row.createdAt()));
    }
    return byTask;
  }

  private void writeTaskTagsChanged(Task task, Tag tag, String action) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("taskId", task.getId().toString());
    payload.put("projectId", task.getProjectId().toString());
    payload.put("tagId", tag.getId().toString());
    payload.put("tagName", tag.getName());
    payload.put("action", action);
    outboxEventRepository.write(
        TASK_EVENTS_TOPIC,
        "TASK_TAGS_CHANGED",
        task.getId(),
        task.getWorkspaceId(),
        objectMapper.writeValueAsString(payload));
  }

  private void rejectDuplicateName(UUID workspaceId, String name, Tag ignoring) {
    boolean sameAsIgnored = ignoring != null && ignoring.getName().equalsIgnoreCase(name);
    if (sameAsIgnored) {
      return;
    }
    if (tagRepository.existsByWorkspaceIdAndNameIgnoreCase(workspaceId, name)) {
      throw duplicateNameException();
    }
  }

  private static BusinessRuleException duplicateNameException() {
    return new BusinessRuleException("Bu isimde bir etiket zaten var.");
  }

  private static String normalize(String name) {
    return name.trim();
  }

  private Tag requireTag(UUID tagId) {
    return tagRepository
        .findById(tagId)
        .orElseThrow(() -> new ResourceNotFoundException("Etiket bulunamadi."));
  }

  private Task requireTask(UUID taskId) {
    // Task.@SQLRestriction zaten silinmisleri filtreler; ayni persistence context'te ONCEDEN
    // yuklenmis bir entity'ye karsi ek savunma icin TaskService.requireTask ile AYNI desen.
    return taskRepository
        .findById(taskId)
        .filter(task -> !task.isDeleted())
        .orElseThrow(() -> new ResourceNotFoundException("Gorev bulunamadi."));
  }

  private static UUID requireWorkspace() {
    UUID workspaceId = TenantContext.getWorkspaceId();
    if (workspaceId == null) {
      throw new BusinessRuleException("Once bir workspace secmelisiniz (X-Workspace-Id header).");
    }
    return workspaceId;
  }
}
