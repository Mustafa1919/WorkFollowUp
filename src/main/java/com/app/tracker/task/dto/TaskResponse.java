package com.app.tracker.task.dto;

import com.app.tracker.dependency.dto.TaskRefResponse;
import com.app.tracker.dependency.service.TaskDependencyService.DependencySummary;
import com.app.tracker.tag.dto.TagResponse;
import com.app.tracker.task.model.Task;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TaskResponse(
    UUID id,
    UUID projectId,
    UUID sprintId,
    UUID parentTaskId,
    int taskNumber,
    String title,
    String status,
    LocalDate dueDate,
    Instant approvedAt,
    Instant createdAt,
    List<TagResponse> tags,
    Integer storyPoint,
    int subtaskCount,
    int completedSubtaskCount,
    List<TaskRefResponse> blocking,
    List<TaskRefResponse> blockedBy) {

  /** PageResponse ile AYNI desen: EI_EXPOSE_REP'e karsi degismez kopya (List.copyOf). */
  public TaskResponse {
    tags = List.copyOf(tags);
    blocking = List.copyOf(blocking);
    blockedBy = List.copyOf(blockedBy);
  }

  /**
   * {@code tags}/{@code storyPoint}/{@code subtaskCount(s)}/{@code dependencies} caller tarafindan
   * verilir: tek-gorev uc noktalari kendi sorgusunu yapar, liste uc noktalari N+1'i onlemek icin
   * TagService.tagsForTasks / TaskCustomFieldRepository.getStoryPoints / TaskService.subtaskCounts
   * / TaskDependencyService.dependenciesForTasks ile ONCEDEN batch yukler.
   */
  public static TaskResponse from(
      Task task,
      List<TagResponse> tags,
      Integer storyPoint,
      int subtaskCount,
      int completedSubtaskCount,
      DependencySummary dependencies) {
    return new TaskResponse(
        task.getId(),
        task.getProjectId(),
        task.getSprintId(),
        task.getParentTaskId(),
        task.getTaskNumber(),
        task.getTitle(),
        task.getStatus(),
        task.getDueDate(),
        task.getApprovedAt(),
        task.getCreatedAt(),
        tags,
        storyPoint,
        subtaskCount,
        completedSubtaskCount,
        dependencies.blocking(),
        dependencies.blockedBy());
  }
}
