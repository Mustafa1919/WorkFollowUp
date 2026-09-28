package com.app.tracker.telemetry;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code usage_events}/{@code error_events} -- outbox_events ile AYNI native-SQL desen, ama RLS'siz
 * (V33 gerekcesi). Consumer'in RLS baglami kurmasina (TenantExecutor.runAs) gerek YOK -- bu
 * tablolar hicbir tenant policy'sine tabi degil. Her metod kendi {@code @Transactional} sinirini
 * tasir (tek INSERT, cross-write atomicity gerekmiyor -- OutboxEventRepository.write'in aksine,
 * cagiranin acik bir transaction icinde olmasi VARSAYILMAZ).
 */
@Repository
public class TelemetryEventRepository {

  private final EntityManager entityManager;

  public TelemetryEventRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Transactional
  public void insertUsageEvent(
      UUID id, UUID workspaceId, UUID userId, String feature, String action, Instant occurredAt) {
    entityManager
        .createNativeQuery(
            "INSERT INTO usage_events (id, workspace_id, user_id, feature, action, occurred_at) "
                + "VALUES (?1, ?2, ?3, ?4, ?5, ?6) ON CONFLICT (id) DO NOTHING")
        .setParameter(1, id)
        .setParameter(2, workspaceId)
        .setParameter(3, userId)
        .setParameter(4, feature)
        .setParameter(5, action)
        .setParameter(6, occurredAt)
        .executeUpdate();
  }

  @Transactional
  public void insertErrorEvent(
      UUID id,
      UUID workspaceId,
      UUID userId,
      String source,
      String errorType,
      String message,
      String path,
      Instant occurredAt) {
    entityManager
        .createNativeQuery(
            "INSERT INTO error_events "
                + "(id, workspace_id, user_id, source, error_type, message, path, occurred_at) "
                + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8) ON CONFLICT (id) DO NOTHING")
        .setParameter(1, id)
        .setParameter(2, workspaceId)
        .setParameter(3, userId)
        .setParameter(4, source)
        .setParameter(5, errorType)
        .setParameter(6, message)
        .setParameter(7, path)
        .setParameter(8, occurredAt)
        .executeUpdate();
  }
}
