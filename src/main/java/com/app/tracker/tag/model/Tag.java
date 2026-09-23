package com.app.tracker.tag.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Workspace duzeyinde etiket (V17__tags.sql). RLS'e tabidir. Proje duzeyinde degil workspace
 * duzeyinde tanimlanir: kucuk ekiplerde etiket anlami/rengi projeler arasi tutarli olmali.
 */
@Entity
@Table(name = "tags")
@Getter
@NoArgsConstructor
public class Tag {

  @Id private UUID id;

  private UUID workspaceId;

  private String name;

  /** {@code #RRGGBB}; DTO seviyesinde ve DB CHECK ile de dogrulanir. */
  private String color;

  private Instant createdAt;

  public static Tag of(UUID id, UUID workspaceId, String name, String color) {
    Tag tag = new Tag();
    tag.id = id;
    tag.workspaceId = workspaceId;
    tag.name = name;
    tag.color = color;
    tag.createdAt = Instant.now();
    return tag;
  }

  public void rename(String name, String color) {
    this.name = name;
    this.color = color;
  }
}
