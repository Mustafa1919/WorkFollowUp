package com.app.tracker.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Alt gorev olusturma (ADR-0021). {@code storyPoint} ve {@code sprintId} YOK: puan ust gorevde
 * tutulur, sprint ust gorevden devralinir. Opsiyonel alanlar boxed/referans tip.
 */
public record CreateSubtaskRequest(
    @NotBlank @Size(max = 255) String title,
    @Size(max = 20000) String description,
    LocalDate dueDate,
    UUID assigneeId,
    @Size(max = 20) List<UUID> tagIds) {

  public CreateSubtaskRequest {
    tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
  }
}
