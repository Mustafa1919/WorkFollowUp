package com.app.tracker.notification.preferences.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateNotificationPreferencesRequest(
    @NotNull Boolean emailOnAssign, @NotNull Boolean emailOnMention) {}
