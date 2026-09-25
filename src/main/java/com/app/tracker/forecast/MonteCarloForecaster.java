package com.app.tracker.forecast;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Dalga 2.2 — olasiliksal tahmin (ADR-0013). {@code MeetingOccurrenceCalculator} gibi saf ve
 * Spring'siz: tohumlanabilir {@link Random} ile testler DETERMINISTIK olabilir.
 *
 * <p>Yontem "bootstrap resampling": gecmis N gunun gunluk throughput ornekleri ({@code
 * dailySamples}, sifir-doldurulmus — hic tamamlanma olmayan gunler de orneklemde YER ALMALI, aksi
 * halde tahmin iyimser sapar) rastgele (yerine koyarak) yeniden secilip binlerce "sanal gelecek"
 * simule edilir.
 *
 * <p>En az {@value #MIN_SAMPLES} tamamlanmis is (pencerenin gun sayisi DEGIL, degerlerin TOPLAMI)
 * yoksa (yeni proje/az kullanim) tahmin YAPILMAZ — az veriyle bootstrap gercek dagilimi temsil
 * etmez, yanlis guven verir. Pencerenin kendisi (kac gun geriye bakildigi) caginin
 * sorumlulugundadir.
 */
public final class MonteCarloForecaster {

  public static final int MIN_SAMPLES = 10;

  private static final int TRIALS = 10_000;

  /** Guvenlik supabi: hicbir orneklemde tamamlanma yoksa (hepsi sifir) sonsuz donguyu keser. */
  private static final int MAX_DAYS = 3650;

  private MonteCarloForecaster() {}

  /**
   * @return 0 (normal), 1 veya 2 DEGIL — bkz. {@link CompletionForecast}. Ornek yetersizse bos.
   */
  public static Optional<CompletionForecast> forecastCompletion(
      List<Long> dailySamples, long remainingItems, LocalDate startDate, long seed) {
    long totalCompleted = sum(dailySamples);
    if (totalCompleted < MIN_SAMPLES) {
      return Optional.empty();
    }
    if (remainingItems <= 0) {
      return Optional.of(
          new CompletionForecast((int) totalCompleted, startDate, startDate, startDate));
    }
    int[] daysNeeded = simulateDaysNeeded(dailySamples, remainingItems, new Random(seed));
    return Optional.of(
        new CompletionForecast(
            (int) totalCompleted,
            startDate.plusDays(percentile(daysNeeded, 0.50)),
            startDate.plusDays(percentile(daysNeeded, 0.85)),
            startDate.plusDays(percentile(daysNeeded, 0.95))));
  }

  /** "X tarihine (bugunden itibaren {@code daysAvailable} gun sonrasina) kadar biter mi?" */
  public static Optional<Double> probabilityWithinDays(
      List<Long> dailySamples, long remainingItems, int daysAvailable, long seed) {
    if (sum(dailySamples) < MIN_SAMPLES) {
      return Optional.empty();
    }
    if (remainingItems <= 0) {
      return Optional.of(1.0);
    }
    if (daysAvailable <= 0) {
      return Optional.of(0.0);
    }
    int successes =
        simulateSuccesses(dailySamples, remainingItems, daysAvailable, new Random(seed));
    return Optional.of(successes / (double) TRIALS);
  }

  private static int simulateSuccesses(
      List<Long> dailySamples, long remainingItems, int daysAvailable, Random rng) {
    int n = dailySamples.size();
    int successes = 0;
    for (int t = 0; t < TRIALS; t++) {
      long done = 0;
      for (int d = 0; d < daysAvailable; d++) {
        done += dailySamples.get(rng.nextInt(n));
      }
      if (done >= remainingItems) {
        successes++;
      }
    }
    return successes;
  }

  private static int[] simulateDaysNeeded(
      List<Long> dailySamples, long remainingItems, Random rng) {
    int n = dailySamples.size();
    int[] daysNeeded = new int[TRIALS];
    for (int t = 0; t < TRIALS; t++) {
      long done = 0;
      int days = 0;
      while (done < remainingItems && days < MAX_DAYS) {
        done += dailySamples.get(rng.nextInt(n));
        days++;
      }
      daysNeeded[t] = days;
    }
    Arrays.sort(daysNeeded);
    return daysNeeded;
  }

  private static int percentile(int[] sortedDays, double p) {
    int idx = (int) Math.ceil(p * sortedDays.length) - 1;
    return sortedDays[Math.max(0, Math.min(sortedDays.length - 1, idx))];
  }

  private static long sum(List<Long> samples) {
    long total = 0;
    for (long sample : samples) {
      total += sample;
    }
    return total;
  }

  /**
   * @param sampleSize pencerede TOPLAM tamamlanan is sayisi (gun sayisi DEGIL).
   * @param p50 kalan is bu tarihe KADAR (dahil) yuzde 50 ihtimalle biter (medyan).
   */
  public record CompletionForecast(int sampleSize, LocalDate p50, LocalDate p85, LocalDate p95) {}
}
