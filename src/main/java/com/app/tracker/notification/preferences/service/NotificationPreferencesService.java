package com.app.tracker.notification.preferences.service;

import com.app.tracker.notification.preferences.model.NotificationPreferences;
import com.app.tracker.notification.preferences.repository.NotificationPreferencesRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kullanicinin e-posta bildirim tercihleri. Tenant baglami GEREKMEZ (tablo RLS'siz, kullaniciya
 * ait). Satir yoksa varsayilan (ikisi de acik) dondurulur/varsayilir — e-posta tuketicileri de AYNI
 * kurali {@link #emailOnAssign} / {@link #emailOnMention} ile uygular.
 */
@Service
public class NotificationPreferencesService {

  private final NotificationPreferencesRepository repository;

  public NotificationPreferencesService(NotificationPreferencesRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public NotificationPreferences get(UUID userId) {
    return repository
        .findById(userId)
        .orElseGet(() -> NotificationPreferences.of(userId, true, true));
  }

  @Transactional
  public NotificationPreferences update(
      UUID userId, boolean emailOnAssign, boolean emailOnMention) {
    NotificationPreferences prefs =
        repository
            .findById(userId)
            .map(
                existing -> {
                  existing.update(emailOnAssign, emailOnMention);
                  return existing;
                })
            .orElseGet(() -> NotificationPreferences.of(userId, emailOnAssign, emailOnMention));
    return repository.save(prefs);
  }

  /** Consumer'lar icin: satir yoksa varsayilan acik. */
  @Transactional(readOnly = true)
  public boolean emailOnAssign(UUID userId) {
    return repository.findById(userId).map(NotificationPreferences::isEmailOnAssign).orElse(true);
  }

  @Transactional(readOnly = true)
  public boolean emailOnMention(UUID userId) {
    return repository.findById(userId).map(NotificationPreferences::isEmailOnMention).orElse(true);
  }
}
