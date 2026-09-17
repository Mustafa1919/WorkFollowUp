package com.app.tracker.core.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** PHASE_1_DETAILED_DESIGN.md Bolum 4.1 — keyset pagination zarfi. */
public record PageResponse<T>(
    List<T> data,
    @JsonProperty("next_cursor") String nextCursor,
    @JsonProperty("has_more") boolean hasMore) {

  public PageResponse(List<T> data, String nextCursor, boolean hasMore) {
    this.data = List.copyOf(data);
    this.nextCursor = nextCursor;
    this.hasMore = hasMore;
  }
}
