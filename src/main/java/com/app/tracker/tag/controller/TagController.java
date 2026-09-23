package com.app.tracker.tag.controller;

import com.app.tracker.tag.dto.TagRequest;
import com.app.tracker.tag.dto.TagResponse;
import com.app.tracker.tag.model.Tag;
import com.app.tracker.tag.service.TagService;
import com.app.tracker.workspace.model.WorkspaceRole;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace duzeyinde etiket yonetimi. Olustur/degistir/sil ADMIN/MANAGER (etiket tanimlari tum
 * projeleri etkiler); listeleme herhangi bir uye (workspace GET endpoint'leriyle AYNI desen,
 * TaskController/SprintController'da oldugu gibi bilerek {@code @PreAuthorize} YOK — uyelik
 * WorkspaceContextFilter'da zaten dogrulanmis).
 */
@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

  private static final String MANAGE =
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "')";

  private final TagService tagService;

  public TagController(TagService tagService) {
    this.tagService = tagService;
  }

  @PostMapping
  @PreAuthorize(MANAGE)
  public ResponseEntity<TagResponse> create(@Valid @RequestBody TagRequest request) {
    Tag tag = tagService.createTag(request.name(), request.color());
    return ResponseEntity.status(HttpStatus.CREATED).body(TagResponse.from(tag));
  }

  @GetMapping
  public List<TagResponse> list() {
    return tagService.listTags().stream().map(TagResponse::from).toList();
  }

  @PutMapping("/{tagId}")
  @PreAuthorize(MANAGE)
  public TagResponse update(@PathVariable UUID tagId, @Valid @RequestBody TagRequest request) {
    return TagResponse.from(tagService.update(tagId, request.name(), request.color()));
  }

  @DeleteMapping("/{tagId}")
  @PreAuthorize(MANAGE)
  public ResponseEntity<Void> delete(@PathVariable UUID tagId) {
    tagService.deleteTag(tagId);
    return ResponseEntity.noContent().build();
  }
}
