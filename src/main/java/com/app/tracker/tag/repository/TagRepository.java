package com.app.tracker.tag.repository;

import com.app.tracker.tag.model.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * RLS zaten workspace'e gore filtreler (bkz. ProjectRepository/SprintRepository ile ayni desen).
 */
public interface TagRepository extends JpaRepository<Tag, UUID> {

  List<Tag> findAllByOrderByNameAsc();

  /** {@code uq_tags_workspace_lower_name} race condition'ina karsi ilk savunma hatti. */
  boolean existsByWorkspaceIdAndNameIgnoreCase(UUID workspaceId, String name);
}
