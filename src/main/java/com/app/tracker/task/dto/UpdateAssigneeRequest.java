package com.app.tracker.task.dto;

import java.util.UUID;

/** {@code assigneeId = null}: atamayi kaldirir. */
public record UpdateAssigneeRequest(UUID assigneeId) {}
