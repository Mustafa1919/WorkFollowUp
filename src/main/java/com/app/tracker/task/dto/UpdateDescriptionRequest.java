package com.app.tracker.task.dto;

import jakarta.validation.constraints.Size;

/**
 * Markdown aciklama; {@code null} veya bos metin aciklamayi kaldirir. Ust sinir V22'deki DB CHECK'i
 * ile ayni.
 */
public record UpdateDescriptionRequest(@Size(max = 20000) String description) {}
