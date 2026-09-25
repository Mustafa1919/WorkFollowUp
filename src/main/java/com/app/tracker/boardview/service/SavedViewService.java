package com.app.tracker.boardview.service;

import com.app.tracker.boardview.model.SavedView;
import com.app.tracker.boardview.repository.SavedViewRepository;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.repository.ProjectRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dalga 1.7 (V27) — kisisel kayitli gorunumler. Tags/Meetings'in aksine workspace-geneli PAYLASILAN
 * bir yapi DEGIL: RLS yalniz workspace'i izole eder, sahiplik (userId) burada servis katmaninda
 * kontrol edilir (CommentService'teki yazan/ADMIN denetimiyle AYNI desen, ADMIN istisnasi olmadan —
 * bir gorunum yalniz sahibine gorunur ve yalniz sahibi silebilir).
 */
@Service
public class SavedViewService {

  private final SavedViewRepository savedViewRepository;
  private final ProjectRepository projectRepository;

  public SavedViewService(
      SavedViewRepository savedViewRepository, ProjectRepository projectRepository) {
    this.savedViewRepository = savedViewRepository;
    this.projectRepository = projectRepository;
  }

  @Transactional
  public SavedView create(UUID projectId, UUID userId, String name, String query) {
    Project project = requireProject(projectId);
    return savedViewRepository.save(
        SavedView.of(UUID.randomUUID(), project.getWorkspaceId(), projectId, userId, name, query));
  }

  @Transactional(readOnly = true)
  public List<SavedView> list(UUID projectId, UUID userId) {
    requireProject(projectId);
    return savedViewRepository.findByProjectIdAndUserIdOrderByNameAsc(projectId, userId);
  }

  @Transactional
  public void delete(UUID viewId, UUID userId) {
    SavedView view =
        savedViewRepository
            .findById(viewId)
            .orElseThrow(() -> new ResourceNotFoundException("Gorunum bulunamadi."));
    if (!view.getUserId().equals(userId)) {
      throw new AccessDeniedException("Yalniz gorunumu kaydeden kisi silebilir.");
    }
    savedViewRepository.delete(view);
  }

  private Project requireProject(UUID projectId) {
    return projectRepository
        .findById(projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Proje bulunamadi."));
  }
}
