package com.app.tracker.workspace.service;

import com.app.tracker.workspace.model.Workspace;
import com.app.tracker.workspace.repository.WorkspaceRepository;
import java.util.List;
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

  /**
   * Workspace'ler arasi calisan worker'lar icin ("Tenant-Iterating"). Transaction burada, servis
   * katmaninda acilir: TenancyGuardAspect repository proxy'sinin DISINDA calisir, bu yuzden
   * transaction'i repository arayuzundeki {@code @Transactional}'a birakmak "transaction disi"
   * hatasi verir.
   */
  @Transactional(readOnly = true)
  public List<UUID> findAllWorkspaceIds() {
    return workspaceRepository.findAllIds();
  }
}
