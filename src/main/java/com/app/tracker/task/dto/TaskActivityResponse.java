package com.app.tracker.task.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Dalga 1.5 — Activity sekmesi satiri. {@code field}/{@code oldValue}/{@code newValue} ham
 * degerlerdir (ornegin durum icin "In Progress"/"Review", etiket icin etiket adi, bagimlilik icin
 * diger gorevin id'si); insan-okunur cumleyi ("Ayse durumu In Progress -> Review yapti") frontend
 * uretir — backend'in event tipi + alan sozlugunu her yerde ikiletmemesi icin.
 */
public record TaskActivityResponse(
    UUID id,
    UUID actorId,
    String actorName,
    String eventType,
    String field,
    Object oldValue,
    Object newValue,
    Instant createdAt) {}
