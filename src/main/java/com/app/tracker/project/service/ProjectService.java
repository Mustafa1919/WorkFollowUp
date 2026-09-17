package com.app.tracker.project.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import com.app.tracker.task.repository.TaskCounterRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PHASE_1_DETAILED_DESIGN.md Bolum 4 — proje CRUD. workspaceId asla request govdesinden ALINMAZ,
 * her zaman WorkspaceContextFilter'in dogruladigi {@link TenantContext}'ten okunur — aksi halde bir
 * client, sahibi olmadigi bir workspaceId'yi govdeye yazarak baska bir tenant'a proje enjekte
 * edebilirdi (IDOR).
 */
@Service
public class ProjectService {

  private final ProjectRepository projectRepository;
  private final TaskCounterRepository taskCounterRepository;

  public ProjectService(
      ProjectRepository projectRepository, TaskCounterRepository taskCounterRepository) {
    this.projectRepository = projectRepository;
    this.taskCounterRepository = taskCounterRepository;
  }

  @Transactional
  public Project createProject(String key, String name) {
    UUID workspaceId = requireWorkspaceContext();
    Project project = projectRepository.save(Project.of(UUID.randomUUID(), workspaceId, key, name));
    taskCounterRepository.initialize(project.getId());
    return project;
  }

  @Transactional(readOnly = true)
  public List<Project> listProjects() {
    // RLS zaten workspace'e gore filtreler; ek bir WHERE kosuluna gerek yok
    // (bkz. PHASE_1_DETAILED_DESIGN Bolum 3: "taskRepository.findAll() cagirsa bile...").
    return projectRepository.findAll();
  }

  private static UUID requireWorkspaceContext() {
    UUID workspaceId = TenantContext.getWorkspaceId();
    if (workspaceId == null) {
      throw new BusinessRuleException("Once bir workspace secmelisiniz (X-Workspace-Id header).");
    }
    return workspaceId;
  }
}
