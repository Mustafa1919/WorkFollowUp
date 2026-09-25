package com.app.tracker.retro.dto;

import java.time.Instant;
import java.util.UUID;

public record RetroItemResponse(
    UUID id,
    String kind,
    String body,
    UUID authorId,
    String authorName,
    UUID taskId,
    Instant createdAt) {}
