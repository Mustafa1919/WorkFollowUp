package com.app.tracker.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Olusturma ve duzenleme AYNI govdeyi kullanir. Ust sinir V23'teki DB CHECK'i ile ayni. */
public record CommentRequest(@NotBlank @Size(max = 10000) String body) {}
