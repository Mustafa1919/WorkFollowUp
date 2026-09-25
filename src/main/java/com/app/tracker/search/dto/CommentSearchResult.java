package com.app.tracker.search.dto;

import com.app.tracker.search.repository.SearchRepository.CommentHit;
import java.util.UUID;

public record CommentSearchResult(
    UUID id,
    UUID taskId,
    UUID projectId,
    String projectKey,
    int taskNumber,
    String taskTitle,
    String snippet) {

  public static CommentSearchResult from(CommentHit hit) {
    return new CommentSearchResult(
        hit.id(),
        hit.taskId(),
        hit.projectId(),
        hit.projectKey(),
        hit.taskNumber(),
        hit.taskTitle(),
        hit.snippet());
  }
}
