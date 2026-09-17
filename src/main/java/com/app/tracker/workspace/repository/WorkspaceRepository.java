package com.app.tracker.workspace.repository;

import com.app.tracker.workspace.model.Workspace;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {}
