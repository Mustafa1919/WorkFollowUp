package com.app.tracker.notification.preferences.dto;

import com.app.tracker.notification.preferences.model.NotificationPreferences;

public record NotificationPreferencesResponse(boolean emailOnAssign, boolean emailOnMention) {

  public static NotificationPreferencesResponse from(NotificationPreferences prefs) {
    return new NotificationPreferencesResponse(prefs.isEmailOnAssign(), prefs.isEmailOnMention());
  }
}
