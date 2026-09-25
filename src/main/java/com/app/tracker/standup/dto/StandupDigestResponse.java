package com.app.tracker.standup.dto;

import com.app.tracker.standup.StandupFacts;
import java.time.Instant;
import java.util.UUID;

public record StandupDigestResponse(
    UUID userId, String userName, StandupFacts facts, String note, Instant createdAt) {}
