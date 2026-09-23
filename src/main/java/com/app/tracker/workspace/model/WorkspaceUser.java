package com.app.tracker.workspace.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DATABASE_SCHEMA.md 2.3 — kullanicilarin hangi workspace'lerde hangi rolle bulunduğunu tutar.
 * RLS'e tabi DEGILDIR: workspace secimi (WorkspaceContextFilter) tenant context kurulmadan ONCE bu
 * tablo uzerinden dogrulanir.
 */
@Entity
@Table(name = "workspace_users")
@IdClass(WorkspaceUserId.class)
@Getter
@NoArgsConstructor
public class WorkspaceUser {

  @Id private UUID workspaceId;

  @Id private UUID userId;

  private String role;

  public static WorkspaceUser of(UUID workspaceId, UUID userId, String role) {
    WorkspaceUser workspaceUser = new WorkspaceUser();
    workspaceUser.workspaceId = workspaceId;
    workspaceUser.userId = userId;
    workspaceUser.role = role;
    return workspaceUser;
  }

  public void changeRole(String newRole) {
    this.role = newRole;
  }
}
