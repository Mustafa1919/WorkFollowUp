package com.app.tracker.analytics.repository;

import com.app.tracker.analytics.model.SprintAnalytics;
import com.app.tracker.sprint.model.Sprint;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SprintAnalyticsRepository extends JpaRepository<SprintAnalytics, UUID> {

  /** Projenin en son kapanan sprint'leri (velocity listesi); en yeni basta. */
  List<SprintAnalytics> findByProjectIdOrderByCompletedAtDesc(UUID projectId, Pageable pageable);

  /**
   * Tamamlanmis ama read model'de karsiligi olmayan sprint'ler: SPRINT_COMPLETED olayi kayboldu /
   * DLT'de bekliyor / worker o sirada kapaliydi ve topic retention'i asildi (bkz. {@code
   * SprintAnalyticsReconciliationJob}).
   */
  @Query(
      "SELECT s FROM Sprint s WHERE s.status = :status AND s.completedAt IS NOT NULL "
          + "AND NOT EXISTS (SELECT 1 FROM SprintAnalytics a WHERE a.sprintId = s.id)")
  List<Sprint> findWithoutAnalytics(@Param("status") String status);
}
