package com.app.tracker.search.controller;

import com.app.tracker.search.dto.SearchResponse;
import com.app.tracker.search.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dalga 1.6 — {@code GET /api/v1/search}, rol sinirsiz (workspace uyeligi yeterli). */
@RestController
public class SearchController {

  private final SearchService searchService;

  public SearchController(SearchService searchService) {
    this.searchService = searchService;
  }

  @GetMapping("/api/v1/search")
  public SearchResponse search(
      @RequestParam("q") String query,
      @RequestParam(value = "limit", required = false) Integer limit) {
    return searchService.search(query, limit);
  }
}
