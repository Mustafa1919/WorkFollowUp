package com.app.tracker.timemachine;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.timemachine.dto.TaskSnapshotResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0017 — Zaman makinesi: bir projenin gecmisteki bir andaki board goruntusunu yeniden kurar.
 */
@Service
public class TimeMachineService {

  private final ProjectRepository projectRepository;
  private final TaskSnapshotRepository taskSnapshotRepository;

  public TimeMachineService(
      ProjectRepository projectRepository, TaskSnapshotRepository taskSnapshotRepository) {
    this.projectRepository = projectRepository;
    this.taskSnapshotRepository = taskSnapshotRepository;
  }

  @Transactional(readOnly = true)
  public List<TaskSnapshotResponse> boardAt(UUID projectId, Instant cutoff) {
    if (cutoff.isAfter(Instant.now())) {
      throw new BusinessRuleException("Kesit zamani gelecekte olamaz.");
    }
    projectRepository
        .findById(projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Proje bulunamadi."));
    return taskSnapshotRepository.boardAt(projectId, cutoff).stream()
        .map(TaskSnapshotResponse::from)
        .toList();
  }
}
