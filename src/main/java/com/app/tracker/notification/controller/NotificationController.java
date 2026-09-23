package com.app.tracker.notification.controller;

import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.core.web.PageResponse;
import com.app.tracker.notification.dto.NotificationResponse;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.service.NotificationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * In-app Inbox: hep KENDI bildirimlerim (bkz. NotificationService javadoc'u). Bilerek
 * {@code @PreAuthorize} YOK — TaskController/TagController'in GET uc noktalariyla AYNI desen,
 * workspace uyeligi WorkspaceContextFilter'da zaten dogrulanmis; buradaki ek kisit rol degil
 * sahiplik (kendi bildirimin), o da servis katmaninda uygulanir.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  private static final int DEFAULT_PAGE_SIZE = 20;

  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @GetMapping
  public PageResponse<NotificationResponse> list(
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly) {
    PageResponse<Notification> page =
        notificationService.listMine(CurrentUser.id(), limit, cursor, unreadOnly);
    return new PageResponse<>(
        page.data().stream().map(NotificationResponse::from).toList(),
        page.nextCursor(),
        page.hasMore());
  }

  @GetMapping("/unread-count")
  public Map<String, Long> unreadCount() {
    return Map.of("count", notificationService.unreadCount(CurrentUser.id()));
  }

  @PostMapping("/{id}/read")
  public NotificationResponse markRead(@PathVariable UUID id) {
    return NotificationResponse.from(notificationService.markRead(id, CurrentUser.id()));
  }

  @PostMapping("/read-all")
  public void markAllRead() {
    notificationService.markAllRead(CurrentUser.id());
  }
}
