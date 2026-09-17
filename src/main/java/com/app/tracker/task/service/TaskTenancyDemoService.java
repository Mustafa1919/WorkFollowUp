package com.app.tracker.task.service;

import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.repository.TaskCounterRepository;
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
  private final TaskCounterRepository taskCounterRepository;

  public TaskTenancyDemoService(
      ProjectRepository projectRepository,
      TaskRepository taskRepository,
      TaskCounterRepository taskCounterRepository) {
    this.projectRepository = projectRepository;
    this.taskRepository = taskRepository;
    this.taskCounterRepository = taskCounterRepository;
  }

  @Transactional
  public Project createProject(UUID id, UUID workspaceId, String key, String name) {
    Project project = projectRepository.save(Project.of(id, workspaceId, key, name));
    taskCounterRepository.initialize(id);
    return project;
  }

  @Transactional
  public Task createTask(UUID id, UUID workspaceId, UUID projectId, String title) {
    int taskNumber = taskCounterRepository.nextNumber(projectId);
    return taskRepository.save(Task.of(id, workspaceId, projectId, taskNumber, title));
  }

  @Transactional(readOnly = true)
  public List<Task> findAllVisibleTasks() {
    return taskRepository.findAll();
  }
}
