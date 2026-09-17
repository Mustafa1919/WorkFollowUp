package com.app.tracker.core.security.dto;

public record LoginResponse(String accessToken, long expiresInSeconds) {}
