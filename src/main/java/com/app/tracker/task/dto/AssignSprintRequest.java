package com.app.tracker.task.dto;

import java.util.UUID;

/** {@code sprintId = null}: gorevi sprint'ten cikarir (backlog'a alir). */
public record AssignSprintRequest(UUID sprintId) {}
