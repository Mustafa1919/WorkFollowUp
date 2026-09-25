package com.app.tracker.forecast;

import java.time.LocalDate;

/**
 * @param available false: proje/sprint'te en az {@value MonteCarloForecaster#MIN_SAMPLES} gunluk
 *     tamamlanma orneklemi yok — diger tum alanlar bu durumda anlamsizdir.
 * @param targetDate sprint kapsaminda sprint bitis tarihi; proje (backlog) kapsaminda {@code null}.
 * @param probabilityByTargetDate yalniz sprint kapsaminda dolu: kalan isin {@code targetDate}'e
 *     kadar bitme olasiligi (0.0-1.0).
 */
public record ForecastResponse(
    boolean available,
    int sampleSize,
    long remainingItems,
    LocalDate p50CompletionDate,
    LocalDate p85CompletionDate,
    LocalDate p95CompletionDate,
    LocalDate targetDate,
    Double probabilityByTargetDate) {

  public static ForecastResponse unavailable(long remainingItems) {
    return new ForecastResponse(false, 0, remainingItems, null, null, null, null, null);
  }
}
