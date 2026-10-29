package com.app.tracker.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PrivacyNoticeAcceptanceRequest(@NotBlank @Size(max = 20) String version) {}
