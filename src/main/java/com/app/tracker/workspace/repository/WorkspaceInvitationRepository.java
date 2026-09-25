package com.app.tracker.workspace.repository;

import com.app.tracker.workspace.model.WorkspaceInvitation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, UUID> {

  Optional<WorkspaceInvitation> findByTokenHash(String tokenHash);

  /**
   * {@code workspace_invitations} RLS'e tabi DEGIL (V25 notu) — workspaceId HER ZAMAN acikca
   * verilmeli.
   */
  List<WorkspaceInvitation> findByWorkspaceIdAndStatusOrderByCreatedAtDesc(
      UUID workspaceId, String status);

  Optional<WorkspaceInvitation> findByWorkspaceIdAndEmailAndStatus(
      UUID workspaceId, String email, String status);
}
