package com.app.tracker.accesstoken.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code expiresInDays} bos birakilirsa token suresiz -- MeetingRequest'teki opsiyonel-alan
 * tuzagindan (ADR gerekcesi Mimari.md'de) kacinmak icin BILEREK boxed {@code Integer}.
 */
public record CreateAccessTokenRequest(
    @NotBlank @Size(max = 100) String name, Integer expiresInDays) {}
