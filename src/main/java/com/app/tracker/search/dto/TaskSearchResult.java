package com.app.tracker.search.dto;

import com.app.tracker.search.repository.SearchRepository.TaskHit;
import java.util.UUID;

public record TaskSearchResult(
    UUID id,
    UUID projectId,
    String projectKey,
    int taskNumber,
    String title,
    String status,
    String snippet) {

  public static TaskSearchResult from(TaskHit hit) {
    return new TaskSearchResult(
        hit.id(),
        hit.projectId(),
        hit.projectKey(),
        hit.taskNumber(),
        hit.title(),
        hit.status(),
        hit.snippet());
  }
}
