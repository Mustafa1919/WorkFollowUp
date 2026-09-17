package com.app.tracker.project.repository;

import com.app.tracker.project.model.Project;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {}
