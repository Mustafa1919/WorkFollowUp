package com.app.tracker.integration.service;

import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.integration.model.WebhookIntegration;
import com.app.tracker.integration.repository.WebhookIntegrationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Webhook entegrasyonu yonetimi (olustur / listele / secret rotasyonu / sil). Tenant izolasyonu RLS
 * ile saglanir: baska workspace'in entegrasyonu {@code findById}'de HIC DONMEZ, yani "baska
 * tenant'in id'sini biliyorum" saldirisi 404 alir. Transaction siniri bilerek burada (servis
 * katmaninda): TenancyGuardAspect repository proxy'sinin disinda kosar.
 */
@Service
@Profile("!migrate")
public class WebhookIntegrationService {

  /** {@code secret} YALNIZ olusturma/rotasyon yanitinda doner; listelemede asla. */
  public record IntegrationWithSecret(WebhookIntegration integration, String secret) {}

  private final WebhookIntegrationRepository repository;
  private final WebhookSecretService secretService;
  private final WebhookIntegrationResolver resolver;

  public WebhookIntegrationService(
      WebhookIntegrationRepository repository,
      WebhookSecretService secretService,
      WebhookIntegrationResolver resolver) {
    this.repository = repository;
    this.secretService = secretService;
    this.resolver = resolver;
  }

  @Transactional
  public IntegrationWithSecret createGithubIntegration() {
    UUID workspaceId = TenantContext.getWorkspaceId();
    if (workspaceId == null) {
      throw new IllegalStateException("Tenant baglami olmadan entegrasyon olusturulamaz.");
    }
    WebhookIntegration saved =
        repository.save(WebhookIntegration.github(UUID.randomUUID(), workspaceId));
    return withSecret(saved);
  }

  @Transactional(readOnly = true)
  public List<WebhookIntegration> list() {
    return repository.findAllByOrderByCreatedAtAsc();
  }

  @Transactional
  public IntegrationWithSecret rotateSecret(UUID integrationId) {
    WebhookIntegration integration = require(integrationId);
    integration.rotateSecret();
    WebhookIntegration saved = repository.save(integration);
    evictAfterCommit(integrationId);
    return withSecret(saved);
  }

  @Transactional
  public void delete(UUID integrationId) {
    repository.delete(require(integrationId));
    evictAfterCommit(integrationId);
  }

  private WebhookIntegration require(UUID integrationId) {
    return repository
        .findById(integrationId)
        .orElseThrow(() -> new ResourceNotFoundException("Entegrasyon bulunamadi."));
  }

  private IntegrationWithSecret withSecret(WebhookIntegration integration) {
    return new IntegrationWithSecret(
        integration,
        secretService.deriveSecret(integration.getId(), integration.getSecretVersion()));
  }

  /**
   * Commit'ten ONCE temizlemek yetmez: iki adim arasinda gelen bir istek eski degeri yeniden
   * cache'ler ve TTL boyunca silinmis/rotate edilmis entegrasyon gecerli kalirdi.
   */
  private void evictAfterCommit(UUID integrationId) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            resolver.evict(integrationId);
          }
        });
  }
}
