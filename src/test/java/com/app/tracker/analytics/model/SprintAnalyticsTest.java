package com.app.tracker.analytics.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Velocity / Spillover tanimlari (bkz. {@link SprintAnalytics} javadoc'u) — saf birim testi. */
class SprintAnalyticsTest {

  private static final Instant T = Instant.parse("2026-09-15T12:00:00Z");

  private static SprintAnalytics recalculated(SprintSnapshot snapshot) {
    SprintAnalytics analytics =
        SprintAnalytics.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    analytics.recalculate("S1", T, snapshot, null, T.plusSeconds(60));
    return analytics;
  }

  @Test
  void velocityIsCompletedPointsAndSpilloverIsTheRemainderOverCommitted() {
    SprintAnalytics a = recalculated(new SprintSnapshot(4, 3, 16, 13));

    assertEquals(13L, a.getCompletedPoints());
    assertEquals(3L, a.getSpilloverPoints());
    assertEquals(new BigDecimal("0.1875"), a.getSpilloverRate());
    assertEquals("S1", a.getSprintName());
    assertEquals(T, a.getCompletedAt());
  }

  @Test
  void rateIsRoundedHalfUpToFourDecimals() {
    assertEquals(
        new BigDecimal("0.3333"), recalculated(new SprintSnapshot(3, 2, 3, 2)).getSpilloverRate());
    assertEquals(
        new BigDecimal("0.6667"), recalculated(new SprintSnapshot(3, 1, 3, 1)).getSpilloverRate());
  }

  @Test
  void rateIsUndefinedWhenNothingWasCommittedInPoints() {
    // Puansiz sprint: "yuzde 0 devir" yaniltici olurdu, oran tanimsiz (null) kalir.
    SprintAnalytics a = recalculated(new SprintSnapshot(5, 2, 0, 0));

    assertNull(a.getSpilloverRate());
    assertEquals(5, a.getCommittedTasks());
    assertEquals(2, a.getCompletedTasks());
  }

  @Test
  void fullyCompletedSprintHasZeroSpillover() {
    SprintAnalytics a = recalculated(new SprintSnapshot(2, 2, 10, 10));

    assertEquals(0L, a.getSpilloverPoints());
    assertEquals(0, a.getSpilloverRate().compareTo(BigDecimal.ZERO));
  }

  @Test
  void recalculatingOverwritesPreviousValues() {
    SprintAnalytics a = recalculated(new SprintSnapshot(4, 3, 16, 13));
    a.recalculate("Renamed", T, new SprintSnapshot(1, 1, 5, 5), null, T.plusSeconds(120));

    assertEquals("Renamed", a.getSprintName());
    assertEquals(5L, a.getCommittedPoints());
    assertEquals(T.plusSeconds(120), a.getCalculatedAt());
  }

  @Test
  void committedAtStartIsNullWhenStartSnapshotIsUnavailable() {
    SprintAnalytics a = recalculated(new SprintSnapshot(4, 3, 16, 13));

    assertNull(a.getCommittedAtStartTasks());
    assertNull(a.getCommittedAtStartPoints());
  }

  @Test
  void committedAtStartTracksTheSeparateStartSnapshot() {
    SprintAnalytics analytics =
        SprintAnalytics.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    analytics.recalculate(
        "S1", T, new SprintSnapshot(5, 3, 20, 13), new SprintSnapshot(3, 0, 12, 0), T);

    assertEquals(3, analytics.getCommittedAtStartTasks());
    assertEquals(12L, analytics.getCommittedAtStartPoints());
    // Kapanis kesiti (committedTasks/Points) etkilenmez — iki ayri kesit.
    assertEquals(5, analytics.getCommittedTasks());
    assertEquals(20L, analytics.getCommittedPoints());
  }
}
