package com.app.tracker.core.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmDto(
    @NotBlank String token,
    @NotBlank @Size(min = 12, message = "Parola en az 12 karakter olmalidir.")
        String newPassword) {}
