package com.app.tracker.tag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Olusturma ve yeniden adlandirma/renklendirme AYNI govde sekli — ikisi de tam alan seti ister. */
public record TagRequest(
    @NotBlank @Size(max = 40) String name,
    @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Renk #RRGGBB formatinda olmalidir.")
        String color) {}
