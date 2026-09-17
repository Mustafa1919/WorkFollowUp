package com.app.tracker.core.tenancy;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Worker'lar icin "Tenant-Iterating" desen (bkz. PHASE_1_DETAILED_DESIGN Bolum 3.1, Kural 3): HTTP
 * istegi disinda calisan kod (Faz2 Outbox Relay, Faz3 Analitik Worker) elinde JWT olmadigi icin
 * tenant context'ini isledigi kaydin workspace_id'sinden programatik olarak kurar.
 *
 * <p>Bu metot kasitli olarak {@code @Transactional} DEGILDIR: context, cagrilan {@code action}
 * icindeki gercek @Transactional servis metodu baslamadan ONCE set edilmis olmalidir — aksi halde
 * {@link TenancyGuardAspect} transaction baslarken hala eski (veya bos) context'i gorur.
 */
@Component
public class TenantExecutor {

  public <T> T runAs(UUID workspaceId, Supplier<T> action) {
    UUID previous = TenantContext.getWorkspaceId();
    TenantContext.setWorkspaceId(workspaceId);
    try {
      return action.get();
    } finally {
      if (previous != null) {
        TenantContext.setWorkspaceId(previous);
      } else {
        TenantContext.clear();
      }
    }
  }

  public void runAs(UUID workspaceId, Runnable action) {
    runAs(
        workspaceId,
        () -> {
          action.run();
          return null;
        });
  }
}
