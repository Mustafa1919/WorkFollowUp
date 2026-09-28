package com.app.tracker.telemetry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Frontend error boundary / window.onerror'dan gelen tek hata raporu. */
public record ErrorReportRequest(
    @NotBlank @Size(max = 200) String errorType,
    @Size(max = 2000) String message,
    @Size(max = 255) String path) {}
