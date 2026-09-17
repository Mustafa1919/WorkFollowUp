package com.app.tracker.workspace.service;

import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * workspace_users RLS'e tabi degildir; bu servis bilerek WorkspaceContextFilter'in tenant context'i
 * kurmadan ONCE cagirdigi sorgudur (bkz. PHASE_1_DETAILED_DESIGN Bolum 3).
 */
@Service
public class WorkspaceMembershipService {

  private final WorkspaceUserRepository workspaceUserRepository;

  public WorkspaceMembershipService(WorkspaceUserRepository workspaceUserRepository) {
    this.workspaceUserRepository = workspaceUserRepository;
  }

  @Transactional(readOnly = true)
  public Optional<String> findRole(UUID userId, UUID workspaceId) {
    return workspaceUserRepository
        .findByWorkspaceIdAndUserId(workspaceId, userId)
        .map(WorkspaceUser::getRole);
  }

  @Transactional
  public void addMember(UUID workspaceId, UUID userId, String role) {
    workspaceUserRepository.save(WorkspaceUser.of(workspaceId, userId, role));
  }
}
