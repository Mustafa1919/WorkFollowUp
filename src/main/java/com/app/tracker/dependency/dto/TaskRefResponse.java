package com.app.tracker.dependency.dto;

import java.util.UUID;

/** Blocking/blockedBy listelerinde kullanilan hafif gorev referansi — tam TaskResponse degil. */
public record TaskRefResponse(UUID id, int taskNumber, String title, String status) {}
