package com.app.tracker.workspace.service;

import com.app.tracker.workspace.model.Workspace;
import com.app.tracker.workspace.repository.WorkspaceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** workspaces tablosu RLS'e tabi degildir (bkz. V2__add_rls_policies.sql basindaki not). */
@Service
public class WorkspaceService {

  private final WorkspaceRepository workspaceRepository;

  public WorkspaceService(WorkspaceRepository workspaceRepository) {
    this.workspaceRepository = workspaceRepository;
  }

  @Transactional
  public Workspace createWorkspace(UUID id, String name) {
    return workspaceRepository.save(Workspace.of(id, name));
  }
}
