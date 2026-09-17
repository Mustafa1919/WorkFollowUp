package com.app.tracker.task.repository;

import com.app.tracker.task.model.Task;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, UUID> {}
