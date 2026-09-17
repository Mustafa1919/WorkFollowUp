package com.app.tracker.workspace.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** DATABASE_SCHEMA.md 2.1 — tenant izolasyonunun tepe noktasi, RLS'e tabi DEGILDIR. */
@Entity
@Table(name = "workspaces")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {

  @Id private UUID id;

  private String name;

  private String planType;

  public static Workspace of(UUID id, String name) {
    return new Workspace(id, name, "free");
  }
}
