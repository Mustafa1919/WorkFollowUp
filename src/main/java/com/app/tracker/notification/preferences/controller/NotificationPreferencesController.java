package com.app.tracker.notification.preferences.controller;

import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.notification.preferences.dto.NotificationPreferencesResponse;
import com.app.tracker.notification.preferences.dto.UpdateNotificationPreferencesRequest;
import com.app.tracker.notification.preferences.service.NotificationPreferencesService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code X-Workspace-Id} GEREKTIRMEZ (bkz. {@code GET /workspaces}, {@code GET /me/tasks} ile ayni
 * desen): tercih workspace'e degil kullaniciya aittir.
 */
@RestController
@RequestMapping("/api/v1/me/notification-preferences")
public class NotificationPreferencesController {

  private final NotificationPreferencesService service;

  public NotificationPreferencesController(NotificationPreferencesService service) {
    this.service = service;
  }

  @GetMapping
  public NotificationPreferencesResponse get() {
    return NotificationPreferencesResponse.from(service.get(CurrentUser.id()));
  }

  @PutMapping
  public NotificationPreferencesResponse update(
      @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
    return NotificationPreferencesResponse.from(
        service.update(CurrentUser.id(), request.emailOnAssign(), request.emailOnMention()));
  }
}
