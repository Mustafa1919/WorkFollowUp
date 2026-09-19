package com.app.tracker.sprint.repository;

import com.app.tracker.sprint.model.Sprint;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SprintRepository extends JpaRepository<Sprint, UUID> {

  List<Sprint> findByProjectIdOrderByStartDateDesc(UUID projectId);

  boolean existsByProjectIdAndStatus(UUID projectId, String status);
}
