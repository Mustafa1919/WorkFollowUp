package com.app.tracker.search.service;

import com.app.tracker.search.dto.CommentSearchResult;
import com.app.tracker.search.dto.SearchResponse;
import com.app.tracker.search.dto.TaskSearchResult;
import com.app.tracker.search.repository.SearchRepository;
import com.app.tracker.search.repository.SearchRepository.TaskHit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dalga 1.6 — global arama. Proje anahtari + gorev numarasi bicimine ("PRJ-12", buyuk/kucuk harf
 * duyarsiz) tam eslesen sorgular dogrudan bulunup sonuc listesinin BASINA konur
 * (GithubEventInterpreter ile AYNI desen: `[A-Z][A-Z0-9]{1,9}-\d{1,9}`, iki modulun birbirinden
 * bagimsiz kucuk kopyalari — paylasilan bir sabit CIKARILMADI, aralarinda gercek bir bagimlilik
 * yok).
 */
@Service
public class SearchService {

  private static final int DEFAULT_LIMIT = 10;
  private static final int MAX_LIMIT = 50;
  private static final Pattern DIRECT_KEY =
      Pattern.compile("^([A-Za-z][A-Za-z0-9]{1,9})-(\\d{1,9})$");

  private final SearchRepository searchRepository;

  public SearchService(SearchRepository searchRepository) {
    this.searchRepository = searchRepository;
  }

  @Transactional(readOnly = true)
  public SearchResponse search(String q, Integer limitParam) {
    String query = q == null ? "" : q.trim();
    if (query.isEmpty()) {
      return new SearchResponse(List.of(), List.of());
    }
    int limit = boundedLimit(limitParam);

    List<TaskHit> taskHits = new ArrayList<>();
    Matcher directMatch = DIRECT_KEY.matcher(query);
    if (directMatch.matches()) {
      searchRepository
          .findByProjectKeyAndNumber(directMatch.group(1), Integer.parseInt(directMatch.group(2)))
          .ifPresent(taskHits::add);
    }
    for (TaskHit hit : searchRepository.searchTasks(query, limit)) {
      if (taskHits.stream().noneMatch(existing -> existing.id().equals(hit.id()))) {
        taskHits.add(hit);
      }
    }
    if (taskHits.size() > limit) {
      taskHits = taskHits.subList(0, limit);
    }

    List<TaskSearchResult> tasks = taskHits.stream().map(TaskSearchResult::from).toList();
    List<CommentSearchResult> comments =
        searchRepository.searchComments(query, limit).stream()
            .map(CommentSearchResult::from)
            .toList();
    return new SearchResponse(tasks, comments);
  }

  private int boundedLimit(Integer limitParam) {
    if (limitParam == null || limitParam <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limitParam, MAX_LIMIT);
  }
}
