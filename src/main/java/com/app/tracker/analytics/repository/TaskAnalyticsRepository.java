package com.app.tracker.analytics.repository;

import com.app.tracker.analytics.model.TaskAnalytics;
import jakarta.persistence.LockModeType;
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
}
