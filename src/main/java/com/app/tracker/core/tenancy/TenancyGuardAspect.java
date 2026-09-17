package com.app.tracker.core.tenancy;

import com.app.tracker.core.config.TransactionManagementConfig;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * PHASE_1_DETAILED_DESIGN.md Bolum 3.1, Kural 1: {@code SET LOCAL}, yalnizca aktif bir transaction
 * icinde etki eder; transaction disinda sessizce (sadece WARNING ile) etkisiz kalir. Bu aspect iki
 * seyi garanti eder: (1) transaction disi calisan tenant-aware kod {@link IllegalStateException}
 * ile durdurulur, (2) transaction icindeyse tenant context veritabani oturumuna {@code
 * set_config(..., true)} (SET LOCAL esdegeri) ile yazilir.
 */
@Aspect
@Component
@Order(TransactionManagementConfig.ORDER + 1)
public class TenancyGuardAspect {

  private final EntityManager entityManager;

  public TenancyGuardAspect(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Pointcut(
      "@within(org.springframework.transaction.annotation.Transactional) || "
          + "@annotation(org.springframework.transaction.annotation.Transactional)")
  void transactionalBoundary() {}

  @Before("transactionalBoundary()")
  public void enforceTenantContext() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Tenant-aware kod aktif bir transaction disinda calistirildi — "
              + "SET LOCAL sessizce etkisiz kalirdi (bkz. PHASE_1_DETAILED_DESIGN, Bolum 3.1).");
    }

    UUID workspaceId = TenantContext.getWorkspaceId();
    if (workspaceId != null) {
      entityManager
          .createNativeQuery("SELECT set_config('app.current_workspace_id', :workspaceId, true)")
          .setParameter("workspaceId", workspaceId.toString())
          .getSingleResult();
    }
  }
}
