package com.app.tracker.boardview.controller;

import com.app.tracker.boardview.dto.SavedViewRequest;
import com.app.tracker.boardview.dto.SavedViewResponse;
import com.app.tracker.boardview.model.SavedView;
import com.app.tracker.boardview.service.SavedViewService;
import com.app.tracker.core.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kisisel kayitli gorunumler; rol siniri YOK (watch/notification-preferences ile AYNI desen — kendi
 * filtre tercihini kaydetmek VIEWER dahil herkese acik, paylasilan veriyi degistirmiyor).
 */
@RestController
public class SavedViewController {

  private final SavedViewService savedViewService;

  public SavedViewController(SavedViewService savedViewService) {
    this.savedViewService = savedViewService;
  }

  @PostMapping("/api/v1/projects/{projectId}/saved-views")
  public ResponseEntity<SavedViewResponse> create(
      @PathVariable UUID projectId, @Valid @RequestBody SavedViewRequest request) {
    SavedView view =
        savedViewService.create(projectId, CurrentUser.id(), request.name(), request.query());
    return ResponseEntity.status(HttpStatus.CREATED).body(SavedViewResponse.from(view));
  }

  @GetMapping("/api/v1/projects/{projectId}/saved-views")
  public List<SavedViewResponse> list(@PathVariable UUID projectId) {
    return savedViewService.list(projectId, CurrentUser.id()).stream()
        .map(SavedViewResponse::from)
        .toList();
  }

  @DeleteMapping("/api/v1/saved-views/{viewId}")
  public ResponseEntity<Void> delete(@PathVariable UUID viewId) {
    savedViewService.delete(viewId, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }
}
