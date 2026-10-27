package com.app.tracker.feedback.controller;

import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.feedback.dto.FeedbackRequest;
import com.app.tracker.feedback.dto.FeedbackResponse;
import com.app.tracker.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gonderme herhangi bir uye (WorkspaceContextFilter'da zaten uyelik dogrulanmis, TagController'daki
 * list() ile AYNI desen); listeleme ADMIN/MANAGER'a ozel -- bir uyenin gonderdigi geri bildirim
 * digerlerine acik bir kaynak degil.
 */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

  private final FeedbackService feedbackService;

  public FeedbackController(FeedbackService feedbackService) {
    this.feedbackService = feedbackService;
  }

  @PostMapping
  public ResponseEntity<FeedbackResponse> submit(@Valid @RequestBody FeedbackRequest request) {
    var feedback = feedbackService.submit(CurrentUser.id(), request.message(), request.pagePath());
    return ResponseEntity.status(HttpStatus.CREATED).body(FeedbackResponse.from(feedback));
  }

  @GetMapping
  @PreAuthorize("@securityGuard.can('FEEDBACK_READ')")
  public List<FeedbackResponse> list() {
    return feedbackService.list().stream().map(FeedbackResponse::from).toList();
  }
}
