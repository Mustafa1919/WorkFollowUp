package com.app.tracker.workspace.model;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** DATABASE_SCHEMA.md 2.3 — workspace_users composite primary key (workspace_id, user_id). */
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceUserId implements Serializable {

  private UUID workspaceId;
  private UUID userId;
}
