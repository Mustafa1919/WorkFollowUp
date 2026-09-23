package com.app.tracker.task.dto;

import java.time.LocalDate;

/** {@code dueDate = null}: gorevi takvimden kaldirir. */
public record UpdateDueDateRequest(LocalDate dueDate) {}
