package com.app.tracker.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Sabit (varyanssiz) gunluk throughput ornekleriyle sonuc RNG tohumundan BAGIMSIZ deterministik
 * olur — bootstrap her zaman ayni degeri secer. Gercekci (varyansli) dagilim davranisi
 * ForecastServiceIntegrationTest'te ayrica dogrulanir.
 */
class MonteCarloForecasterTest {

  @Test
  void forecastIsEmptyWithFewerThanMinSamples() {
    // Toplam tamamlanan = 9 (< MIN_SAMPLES=10), gun sayisi degil bu esigi belirler.
    List<Long> samples = List.of(1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 0L, 0L, 0L);
    assertTrue(
        MonteCarloForecaster.forecastCompletion(samples, 50, LocalDate.of(2026, 1, 1), 1L)
            .isEmpty());
    assertTrue(MonteCarloForecaster.probabilityWithinDays(samples, 50, 10, 1L).isEmpty());
  }

  @Test
  void forecastWithConstantThroughputIsDeterministic() {
    // Her gun tam 5 tamamlanma: 50 is icin tam 10 gun gerekir, varyans yok.
    List<Long> samples = Collections.nCopies(12, 5L);
    LocalDate start = LocalDate.of(2026, 1, 1);

    var forecast = MonteCarloForecaster.forecastCompletion(samples, 50, start, 42L);

    assertTrue(forecast.isPresent());
    assertEquals(start.plusDays(10), forecast.get().p50());
    assertEquals(start.plusDays(10), forecast.get().p85());
    assertEquals(start.plusDays(10), forecast.get().p95());
    assertEquals(60, forecast.get().sampleSize()); // toplam tamamlanan = 12 gun * 5
  }

  @Test
  void forecastWithNoRemainingItemsReturnsStartDateImmediately() {
    List<Long> samples = Collections.nCopies(12, 5L);
    LocalDate start = LocalDate.of(2026, 1, 1);

    var forecast = MonteCarloForecaster.forecastCompletion(samples, 0, start, 42L);

    assertTrue(forecast.isPresent());
    assertEquals(start, forecast.get().p50());
    assertEquals(start, forecast.get().p95());
  }

  @Test
  void probabilityWithinDaysIsCertainWhenExactlyEnoughTimeGiven() {
    List<Long> samples = Collections.nCopies(12, 5L);

    Optional<Double> exact = MonteCarloForecaster.probabilityWithinDays(samples, 50, 10, 7L);
    Optional<Double> oneDayShort = MonteCarloForecaster.probabilityWithinDays(samples, 50, 9, 7L);
    Optional<Double> plentyOfTime = MonteCarloForecaster.probabilityWithinDays(samples, 50, 20, 7L);

    assertEquals(1.0, exact.orElseThrow());
    assertEquals(0.0, oneDayShort.orElseThrow());
    assertEquals(1.0, plentyOfTime.orElseThrow());
  }

  @Test
  void probabilityWithinDaysHandlesEdgeCases() {
    List<Long> samples = Collections.nCopies(12, 5L);

    assertEquals(1.0, MonteCarloForecaster.probabilityWithinDays(samples, 0, 5, 1L).orElseThrow());
    assertEquals(0.0, MonteCarloForecaster.probabilityWithinDays(samples, 10, 0, 1L).orElseThrow());
  }

  @Test
  void extremelySlowThroughputCapsAtMaxDaysSafetyValve() {
    // Sabit gunluk 1 tamamlanma + gercekci olmayan buyuk bir hedef: gercek gun sayisi
    // (1.000.000) MAX_DAYS(3650) guvenlik supabini asar, sonsuz donguye girmez.
    List<Long> samples = Collections.nCopies(12, 1L);
    LocalDate start = LocalDate.of(2026, 1, 1);

    var forecast = MonteCarloForecaster.forecastCompletion(samples, 1_000_000, start, 3L);

    assertTrue(forecast.isPresent());
    assertEquals(start.plusDays(3650), forecast.get().p50());
    assertEquals(start.plusDays(3650), forecast.get().p95());
  }
}
