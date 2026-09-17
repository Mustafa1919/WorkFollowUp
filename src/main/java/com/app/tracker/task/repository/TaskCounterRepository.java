package com.app.tracker.task.repository;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * PHASE_1_DETAILED_DESIGN.md Bolum 6.1 — {@code task_number} yalnizca bu iki metotla uretilir/
 * baslatilir. {@code SELECT MAX()+1} KESINLIKLE kullanilmaz (race condition). {@code nextNumber},
 * satir kilidi altinda {@code UPDATE ... RETURNING} calistirir; caller'in ZATEN acik bir
 * transaction icinde olmasi (Task INSERT'i ile AYNI transaction) zorunludur — aksi halde kilit
 * INSERT ile ayni atomiklige sahip olmaz.
 */
@Repository
public class TaskCounterRepository {

  private final EntityManager entityManager;

  public TaskCounterRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public void initialize(UUID projectId) {
    entityManager
        .createNativeQuery("INSERT INTO task_counters (project_id, last_number) VALUES (?1, 0)")
        .setParameter(1, projectId)
        .executeUpdate();
  }

  public int nextNumber(UUID projectId) {
    Number lastNumber =
        (Number)
            entityManager
                .createNativeQuery(
                    "UPDATE task_counters SET last_number = last_number + 1 "
                        + "WHERE project_id = ?1 RETURNING last_number")
                .setParameter(1, projectId)
                .getSingleResult();
    return lastNumber.intValue();
  }
}
