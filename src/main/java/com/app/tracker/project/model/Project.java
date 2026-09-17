package com.app.tracker.project.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** DATABASE_SCHEMA.md 2.4 — RLS'e tabidir (bkz. V2__add_rls_policies.sql). */
@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Project {

  @Id private UUID id;

  private UUID workspaceId;

  private String key;

  private String name;

  public static Project of(UUID id, UUID workspaceId, String key, String name) {
    return new Project(id, workspaceId, key, name);
  }
}
