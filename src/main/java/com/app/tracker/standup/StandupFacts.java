package com.app.tracker.standup;

import java.util.List;

/**
 * Dalga 2.3 — bir kullanicinin bir occurrence icin "olgulari" (facts). JSONB olarak saklanir
 * ({@code standup_digests.facts}); insan-okunur cumleye cevirme frontend'de yapilir (Activity
 * sekmesiyle AYNI is bolumu ilkesi). LLM katmani v1'de YOK — bu yapi ileride isteğe bagli bir
 * ozetleyicinin girdisi olabilir (Hedefler.md karari).
 */
public record StandupFacts(
    List<StandupTaskRef> completedYesterday,
    List<StandupTaskRef> progressedYesterday,
    List<StandupTaskRef> inProgress,
    List<StandupTaskRef> blocked,
    List<StandupTaskRef> aging,
    List<StandupTaskRef> githubActivity) {

  public StandupFacts {
    completedYesterday = List.copyOf(completedYesterday);
    progressedYesterday = List.copyOf(progressedYesterday);
    inProgress = List.copyOf(inProgress);
    blocked = List.copyOf(blocked);
    aging = List.copyOf(aging);
    githubActivity = List.copyOf(githubActivity);
  }
}
