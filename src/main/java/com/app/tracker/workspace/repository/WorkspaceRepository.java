package com.app.tracker.workspace.repository;

import com.app.tracker.workspace.model.Workspace;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

  /**
   * Workspace'ler arasi calisan worker'lar icin ("Tenant-Iterating"); workspaces RLS'e tabi
   * DEGILDIR (V2 notu). Sadece id doner: tum entity'leri yuklemek gereksiz. Transaction'i CAGIRAN
   * acar (bkz. {@code WorkspaceService.findAllWorkspaceIds}).
   */
  @Query("SELECT w.id FROM Workspace w")
  List<UUID> findAllIds();
}
