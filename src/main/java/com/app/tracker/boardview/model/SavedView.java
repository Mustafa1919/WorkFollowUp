package com.app.tracker.boardview.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Dalga 1.7 (V27) — kisisel, proje duzeyinde kayitli Kanban filtresi. {@code query} sunucuda hic
 * yorumlanmayan opak bir JSON metni (BoardPage.tsx'teki URL filtre parametrelerinin bir kopyasi).
 */
@Entity
@Table(name = "saved_views")
@Getter
@NoArgsConstructor
public class SavedView {

  @Id private UUID id;

  private UUID workspaceId;

  private UUID projectId;

  private UUID userId;

  private String name;

  private String query;

  private Instant createdAt;

  public static SavedView of(
      UUID id, UUID workspaceId, UUID projectId, UUID userId, String name, String query) {
    SavedView view = new SavedView();
    view.id = id;
    view.workspaceId = workspaceId;
    view.projectId = projectId;
    view.userId = userId;
    view.name = name;
    view.query = query;
    view.createdAt = Instant.now();
    return view;
  }
}
