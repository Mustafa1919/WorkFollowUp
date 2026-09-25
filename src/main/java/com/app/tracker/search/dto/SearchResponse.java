package com.app.tracker.search.dto;

import java.util.List;

public record SearchResponse(List<TaskSearchResult> tasks, List<CommentSearchResult> comments) {

  /** PageResponse/TaskResponse ile AYNI desen: EI_EXPOSE_REP'e karsi degismez kopya. */
  public SearchResponse {
    tasks = List.copyOf(tasks);
    comments = List.copyOf(comments);
  }
}
