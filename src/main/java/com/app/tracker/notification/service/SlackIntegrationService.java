package com.app.tracker.notification.service;

import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.notification.model.SlackIntegration;
import com.app.tracker.notification.repository.SlackIntegrationRepository;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace'in Slack entegrasyonu: yonetim (olustur-degistir / oku / sil) VE bildirim worker'i icin
 * "gonderilecek adres" sorgusu. Tenant izolasyonu RLS ile saglanir ve tenant baglami {@link
 * TenantContext}'ten okunur (HTTP'de JWT'den, worker'da TenantExecutor'dan).
 *
 * <p>Adres ASLA yanitta veya log'da gorunmez; yalniz {@link #findActiveWebhookUrl} cozer ve yalniz
 * dahili gonderim yoluna verir. Transaction siniri bilerek burada: TenancyGuardAspect repository
 * proxy'sinin disinda kosar.
 */
@Service
@Profile("!migrate")
public class SlackIntegrationService {

  private final SlackIntegrationRepository repository;
  private final SlackUrlCipher cipher;

  public SlackIntegrationService(SlackIntegrationRepository repository, SlackUrlCipher cipher) {
    this.repository = repository;
    this.cipher = cipher;
  }

  /**
   * Adresi dogrular (allow-list), sifreler ve kaydeder; kayit varsa degistirir (PUT semantigi).
   *
   * @param enabled {@code null} ise {@code true}
   */
  @Transactional
  public SlackIntegration upsert(String webhookUrl, Boolean enabled) {
    SlackWebhookUrlPolicy.validate(webhookUrl);
    UUID workspaceId = requireWorkspace();
    String encrypted = cipher.encrypt(workspaceId, webhookUrl);
    boolean isEnabled = enabled == null || enabled;
    SlackIntegration integration =
        repository
            .findById(workspaceId)
            .map(
                existing -> {
                  existing.update(encrypted, isEnabled);
                  return existing;
                })
            .orElseGet(() -> SlackIntegration.of(workspaceId, encrypted, isEnabled));
    return repository.save(integration);
  }

  @Transactional(readOnly = true)
  public SlackIntegration get() {
    return repository
        .findById(requireWorkspace())
        .orElseThrow(() -> new ResourceNotFoundException("Slack entegrasyonu bulunamadi."));
  }

  @Transactional
  public void delete() {
    repository.delete(
        repository
            .findById(requireWorkspace())
            .orElseThrow(() -> new ResourceNotFoundException("Slack entegrasyonu bulunamadi.")));
  }

  /**
   * Bildirim worker'i icin. Entegrasyon yoksa veya kapaliysa bos. Kayitli adres allow-list'ten
   * TEKRAR gecirilir (savunma derinligi: DB'ye baska yoldan yazilmis bir deger gonderime ulasamaz);
   * gecmezse hata firlatir — sessizce atlamak bozuk veriyi gizlerdi.
   */
  @Transactional(readOnly = true)
  public Optional<URI> findActiveWebhookUrl() {
    UUID workspaceId = requireWorkspace();
    return repository
        .findById(workspaceId)
        .filter(SlackIntegration::isEnabled)
        .map(
            integration ->
                SlackWebhookUrlPolicy.validate(
                    cipher.decrypt(workspaceId, integration.getWebhookUrlEncrypted())));
  }

  private static UUID requireWorkspace() {
    UUID workspaceId = TenantContext.getWorkspaceId();
    if (workspaceId == null) {
      throw new IllegalStateException("Tenant baglami olmadan Slack entegrasyonuna erisilemez.");
    }
    return workspaceId;
  }
}
