package com.app.tracker.task.repository;

import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * DATABASE_SCHEMA.md 2.8 — task_events append-only audit tablosu. Tam bir JPA entity + JSONB type
 * converter kurmak yerine (bu artimda okuma ihtiyaci yok, sadece Faz3 analitik icin biriktirme var)
 * dogrudan native INSERT ile yaziliyor — TaskCounterRepository ile ayni desen.
 */
@Repository
public class TaskEventRepository {

  private final EntityManager entityManager;
  private final ObjectMapper objectMapper;

  public TaskEventRepository(EntityManager entityManager, ObjectMapper objectMapper) {
    this.entityManager = entityManager;
    this.objectMapper = objectMapper;
  }

  public void recordStatusChange(UUID taskId, UUID actorId, String oldStatus, String newStatus) {
    String oldValueJson = objectMapper.writeValueAsString(Map.of("status", oldStatus));
    String newValueJson = objectMapper.writeValueAsString(Map.of("status", newStatus));
    entityManager
        .createNativeQuery(
            "INSERT INTO task_events (id, task_id, actor_id, event_type, old_value, new_value, created_at) "
                // "?4 ::jsonb" (araya BOSLUK) — bkz. OutboxEventRepository.write javadoc'u: bitisik
                // "?N::" Hibernate 7'de "Ordinal parameter label was not an integer" ile patliyor.
                // Bu metot Faz2'den ONCE hicbir entegrasyon testinde gercek Postgres'e karsi
                // calismamisti (updateStatus'u cagiran bir test yoktu) — bu yuzden simdiye kadar
                // fark edilmemis, gercek bir latent bug'du.
                + "VALUES (?1, ?2, ?3, 'status_changed', ?4 ::jsonb, ?5 ::jsonb, NOW())")
        .setParameter(1, UUID.randomUUID())
        .setParameter(2, taskId)
        .setParameter(3, actorId)
        .setParameter(4, oldValueJson)
        .setParameter(5, newValueJson)
        .executeUpdate();
  }
}
