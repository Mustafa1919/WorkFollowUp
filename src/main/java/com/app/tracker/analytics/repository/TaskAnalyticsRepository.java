package com.app.tracker.analytics.repository;

import com.app.tracker.analytics.model.TaskAnalytics;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskAnalyticsRepository extends JpaRepository<TaskAnalytics, UUID> {

  /** Ayni gorevin olaylarini isleyen esizamanli iki consumer'i satir kilidiyle serilestirir. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM TaskAnalytics a WHERE a.taskId = :taskId")
  Optional<TaskAnalytics> findByIdForUpdate(@Param("taskId") UUID taskId);

  /**
   * Aging WIP (Dalga 2.1): en az bir kez "In Progress"e girmis ama HENUZ Done olmamis gorevler
   * (reopen edilip tekrar acilanlar dahil - {@code doneAt} o durumda zaten NULL'dir, bkz. {@link
   * TaskAnalytics#applyStatusChange}).
   */
  @Query(
      "SELECT a FROM TaskAnalytics a WHERE a.projectId = :projectId "
          + "AND a.doneAt IS NULL AND a.firstInProgressAt IS NOT NULL")
  List<TaskAnalytics> findOpenByProjectId(@Param("projectId") UUID projectId);
}
