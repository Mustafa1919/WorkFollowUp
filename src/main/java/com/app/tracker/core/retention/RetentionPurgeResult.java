package com.app.tracker.core.retention;

/** Bir {@link RetentionPurgeJob#purge()} kosusunda tablo basina silinen satir sayilari. */
public record RetentionPurgeResult(
    int refreshTokens,
    int verificationTokens,
    int usageEvents,
    int errorEvents,
    int undeliveredEmailOutbox) {}
