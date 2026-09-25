package com.app.tracker.boardview.repository;

import com.app.tracker.boardview.model.SavedView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** RLS zaten workspace'e gore filtreler (TagRepository ile ayni desen); proje+kullanici burada. */
public interface SavedViewRepository extends JpaRepository<SavedView, UUID> {

  List<SavedView> findByProjectIdAndUserIdOrderByNameAsc(UUID projectId, UUID userId);
}
