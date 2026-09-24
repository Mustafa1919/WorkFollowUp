package com.app.tracker.comment.controller;

import com.app.tracker.comment.dto.CommentRequest;
import com.app.tracker.comment.dto.CommentResponse;
import com.app.tracker.comment.model.Comment;
import com.app.tracker.comment.service.CommentService;
import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.workspace.model.WorkspaceRole;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Urunlestirme Dalga 1.2 — yorumlar. Okuma rol sinirsiz (TaskController/TagController GET
 * desenleriyle AYNI); olusturma diger gorev mutasyonlariyla AYNI (ADMIN/MANAGER/DEVELOPER, VIEWER
 * yazamaz). Duzenleme/silme icin controller'da rol siniri YOK — yetki (yazan veya ADMIN)
 * CommentService'te kontrol edilir; boylece yazan-degil-ama-DEVELOPER senaryosu servis katmaninda
 * tek bir yerde karar verilir (ADR-0009).
 */
@RestController
public class CommentController {

  private static final int MAX_PAGE_SIZE = 200;

  private final CommentService commentService;

  public CommentController(CommentService commentService) {
    this.commentService = commentService;
  }

  @GetMapping("/api/v1/tasks/{taskId}/comments")
  public PageResponse<CommentResponse> list(
      @PathVariable UUID taskId,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor) {
    int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    PageResponse<Comment> page = commentService.listComments(taskId, boundedLimit, cursor);
    return new PageResponse<>(
        page.data().stream().map(CommentResponse::from).toList(),
        page.nextCursor(),
        page.hasMore());
  }

  @PostMapping("/api/v1/tasks/{taskId}/comments")
  @PreAuthorize(
      "@securityGuard.hasCurrentWorkspaceRole('"
          + WorkspaceRole.ADMIN
          + "', '"
          + WorkspaceRole.MANAGER
          + "', '"
          + WorkspaceRole.DEVELOPER
          + "')")
  public ResponseEntity<CommentResponse> create(
      @PathVariable UUID taskId, @Valid @RequestBody CommentRequest request) {
    Comment comment = commentService.addComment(taskId, request.body(), CurrentUser.id());
    return ResponseEntity.status(HttpStatus.CREATED).body(CommentResponse.from(comment));
  }

  @PatchMapping("/api/v1/comments/{commentId}")
  public CommentResponse edit(
      @PathVariable UUID commentId, @Valid @RequestBody CommentRequest request) {
    return CommentResponse.from(
        commentService.editComment(commentId, request.body(), CurrentUser.id()));
  }

  @DeleteMapping("/api/v1/comments/{commentId}")
  public ResponseEntity<Void> delete(@PathVariable UUID commentId) {
    commentService.deleteComment(commentId, CurrentUser.id());
    return ResponseEntity.noContent().build();
  }
}
