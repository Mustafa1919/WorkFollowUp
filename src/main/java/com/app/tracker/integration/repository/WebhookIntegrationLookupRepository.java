package com.app.tracker.integration.repository;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingestion yolunun tenant baglami OLMADAN entegrasyon cozmesi: V13'teki {@code
 * resolve_webhook_integration} SECURITY DEFINER fonksiyonunu cagirir. {@code webhook_integrations}
 * dogrudan sorgulanamaz (RLS, tenant baglami yokken hic satir dondurmez); bu, kimlik dogrulamadan
 * ONCE tenant'i bulmanin dar ve bilincli tek yoludur.
 */
@Repository
public class WebhookIntegrationLookupRepository {

  /**
   * {@code secretVersion} imza secret'ini turetmek icin, {@code workspaceId} olay yonlendirmek
   * icin.
   */
  public record Resolved(UUID id, UUID workspaceId, int secretVersion) {}

  private final EntityManager entityManager;

  public WebhookIntegrationLookupRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Transactional(readOnly = true)
  public Optional<Resolved> resolve(UUID integrationId) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT workspace_id, provider, secret_version "
                    + "FROM resolve_webhook_integration(?1)")
            .setParameter(1, integrationId)
            .getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] row = rows.get(0);
    return Optional.of(new Resolved(integrationId, (UUID) row[0], ((Number) row[2]).intValue()));
  }
}
