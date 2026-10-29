package com.app.tracker.core.security.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 12, message = "Parola en az 12 karakter olmalidir.") String password,
    @NotBlank String fullName,
    @NotNull @AssertTrue Boolean privacyNoticeAccepted,
    @NotBlank @Size(max = 20) String privacyNoticeVersion) {}
