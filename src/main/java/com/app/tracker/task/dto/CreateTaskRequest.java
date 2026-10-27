package com.app.tracker.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Yalniz {@code title} zorunlu. Digerleri opsiyonel ve BOXED/referans tip (primitif eksik alan
 * Jackson'da 400 verip {@code @PreAuthorize} 403'unu maskelerdi). {@code dueDate}: takvimden hizli
 * eklemede gorev dogrudan o gune yazilir. Opsiyonel alanlar {@code TaskCreationService} ile ayni
 * transaction'da mevcut TaskService/TagService metotlariyla uygulanir.
 */
public record CreateTaskRequest(
    @NotBlank @Size(max = 255) String title,
    LocalDate dueDate,
    @Size(max = 20000) String description,
    UUID assigneeId,
    @Min(0) @Max(100) Integer storyPoint,
    UUID sprintId,
    @Size(max = 20) List<UUID> tagIds) {

  public CreateTaskRequest {
    tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
  }
}
