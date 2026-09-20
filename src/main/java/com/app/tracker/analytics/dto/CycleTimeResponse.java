package com.app.tracker.analytics.dto;

/**
 * Son {@code days} gunde tamamlanan ve cycle time'i tanimli gorevlerin dagilimi (saniye). {@code
 * sampleSize == 0} ise istatistik alanlari {@code null}'dur.
 */
public record CycleTimeResponse(
    int days,
    long sampleSize,
    Double averageSeconds,
    Double medianSeconds,
    Double p85Seconds,
    Double p95Seconds) {}
