package com.app.tracker.project.repository;

import com.app.tracker.project.model.Project;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

  /**
   * (workspace_id, key) essizdir ve workspace kisitini RLS uygular; bu yuzden anahtar tek bir
   * tenant baglami icinde en fazla bir satir dondurur.
   */
  Optional<Project> findByKey(String key);
}
