package com.app.tracker.workspace.repository;

import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.model.WorkspaceUserId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceUserRepository extends JpaRepository<WorkspaceUser, WorkspaceUserId> {

  Optional<WorkspaceUser> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

  List<WorkspaceUser> findByUserId(UUID userId);

  /**
   * {@code workspace_users} RLS'e tabi DEGIL (V2 notu) — workspaceId HER ZAMAN acikca verilmeli.
   */
  List<WorkspaceUser> findByWorkspaceId(UUID workspaceId);

  /** "Son ADMIN kaldirilamaz/rolu degistirilemez" kuralinin dayandigi sayim. */
  long countByWorkspaceIdAndRole(UUID workspaceId, String role);
}
