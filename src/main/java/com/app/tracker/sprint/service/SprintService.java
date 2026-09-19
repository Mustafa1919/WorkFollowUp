package com.app.tracker.sprint.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.outbox.OutboxEventRepository;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.sprint.model.Sprint;
import com.app.tracker.sprint.model.SprintStatus;
import com.app.tracker.sprint.repository.SprintRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 1.1 — sprint yasam dongusu. {@code SPRINT_COMPLETED} olayi
 * Analitik Worker'in Velocity hesaplamasini tetikler; payload'daki {@code completedAt}, worker'in
 * sprint uyeligini task_events tarihcesinden yeniden kurarken kullanacagi degismez kesit zamanidir.
 * Olay outbox uzerinden yazilir (dual-write yok, bkz. Faz 2).
 */
@Service
public class SprintService {

  static final String SPRINT_EVENTS_TOPIC = "sprint.events";

  private final SprintRepository sprintRepository;
  private final ProjectRepository projectRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  public SprintService(
      SprintRepository sprintRepository,
      ProjectRepository projectRepository,
      OutboxEventRepository outboxEventRepository,
      ObjectMapper objectMapper) {
    this.sprintRepository = sprintRepository;
    this.projectRepository = projectRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public Sprint createSprint(
      UUID projectId, String name, String goal, LocalDate startDate, LocalDate endDate) {
    if (!endDate.isAfter(startDate)) {
      throw new BusinessRuleException("Bitis tarihi baslangic tarihinden sonra olmalidir.");
    }
    // RLS: baska tenant'in projesi burada BULUNAMAZ (bkz. TaskService.requireProject).
    Project project = requireProject(projectId);
    return sprintRepository.save(
        Sprint.planned(
            UUID.randomUUID(),
            project.getWorkspaceId(),
            project.getId(),
            name,
            goal,
            startDate,
            endDate));
  }

  @Transactional(readOnly = true)
  public List<Sprint> listSprints(UUID projectId) {
    requireProject(projectId);
    return sprintRepository.findByProjectIdOrderByStartDateDesc(projectId);
  }

  @Transactional
  public Sprint startSprint(UUID sprintId) {
    Sprint sprint = requireSprint(sprintId);
    // On kontrol okunakli hata icindir; asil garanti one_active_sprint partial unique index'idir
    // (iki esizamanli start'tan biri asagidaki flush'ta DataIntegrityViolationException alir).
    if (sprintRepository.existsByProjectIdAndStatus(sprint.getProjectId(), SprintStatus.ACTIVE)) {
      throw new BusinessRuleException("Bu projede zaten aktif bir sprint var.");
    }
    sprint.start(Instant.now());
    try {
      sprintRepository.saveAndFlush(sprint);
    } catch (DataIntegrityViolationException e) {
      throw new BusinessRuleException("Bu projede zaten aktif bir sprint var.");
    }
    writeEvent("SPRINT_STARTED", sprint, sprint.getStartedAt(), "startedAt");
    return sprint;
  }

  @Transactional
  public Sprint completeSprint(UUID sprintId) {
    Sprint sprint = requireSprint(sprintId);
    sprint.complete(Instant.now());
    sprintRepository.save(sprint);
    writeEvent("SPRINT_COMPLETED", sprint, sprint.getCompletedAt(), "completedAt");
    return sprint;
  }

  private void writeEvent(String eventType, Sprint sprint, Instant at, String atField) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sprintId", sprint.getId().toString());
    payload.put("projectId", sprint.getProjectId().toString());
    payload.put("name", sprint.getName());
    payload.put(atField, at.toString());
    outboxEventRepository.write(
        SPRINT_EVENTS_TOPIC,
        eventType,
        sprint.getId(),
        sprint.getWorkspaceId(),
        objectMapper.writeValueAsString(payload));
  }

  private Project requireProject(UUID projectId) {
    return projectRepository
        .findById(projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Proje bulunamadi."));
  }

  private Sprint requireSprint(UUID sprintId) {
    return sprintRepository
        .findById(sprintId)
        .orElseThrow(() -> new ResourceNotFoundException("Sprint bulunamadi."));
  }
}
