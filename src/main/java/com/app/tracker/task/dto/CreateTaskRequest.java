package com.app.tracker.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** {@code dueDate} opsiyonel: takvimden hizli eklemede gorev dogrudan o gune yazilir. */
public record CreateTaskRequest(@NotBlank @Size(max = 255) String title, LocalDate dueDate) {}
