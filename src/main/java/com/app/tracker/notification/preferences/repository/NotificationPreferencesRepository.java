package com.app.tracker.notification.preferences.repository;

import com.app.tracker.notification.preferences.model.NotificationPreferences;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** RLS'e tabi degil (V24 yorumu) — kullaniciya ait, workspace'e degil. */
public interface NotificationPreferencesRepository
    extends JpaRepository<NotificationPreferences, UUID> {}
