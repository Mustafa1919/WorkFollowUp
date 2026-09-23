package com.app.tracker.tag.dto;

import com.app.tracker.tag.model.Tag;
import java.time.Instant;
import java.util.UUID;

/** Hem {@code GET /api/v1/tags} hem TaskResponse.tags icinde AYNI sekil kullanilir. */
public record TagResponse(UUID id, String name, String color, Instant createdAt) {

  public static TagResponse from(Tag tag) {
    return new TagResponse(tag.getId(), tag.getName(), tag.getColor(), tag.getCreatedAt());
  }
}
