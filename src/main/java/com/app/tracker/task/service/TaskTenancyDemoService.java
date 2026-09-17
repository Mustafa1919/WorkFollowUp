package com.app.tracker.task.service;

import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PHASE_1_DETAILED_DESIGN.md Bolum 3.1, Kural 1: repository cagrilari yalniz @Transactional servis
 * metotlari icinde yapilir; boylece {@link com.app.tracker.core.tenancy.TenancyGuardAspect} her
 * cagrida devreye girer. Bu servis, RLS izolasyonunu dogrulayan entegrasyon testi icin yazma/ okuma
 * sinirini temsil eder.
 */
@Service
public class TaskTenancyDemoService {

  private final ProjectRepository projectRepository;
  private final TaskRepository taskRepository;

  public TaskTenancyDemoService(
      ProjectRepository projectRepository, TaskRepository taskRepository) {
    this.projectRepository = projectRepository;
    this.taskRepository = taskRepository;
  }

  @Transactional
  public Project createProject(UUID id, UUID workspaceId, String key, String name) {
    return projectRepository.save(Project.of(id, workspaceId, key, name));
  }

  @Transactional
  public Task createTask(UUID id, UUID workspaceId, UUID projectId, int taskNumber, String title) {
    return taskRepository.save(Task.of(id, workspaceId, projectId, taskNumber, title));
  }

  @Transactional(readOnly = true)
  public List<Task> findAllVisibleTasks() {
    return taskRepository.findAll();
  }
}
