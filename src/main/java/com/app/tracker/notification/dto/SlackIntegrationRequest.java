package com.app.tracker.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code webhookUrl} bicim dogrulamasi (allow-list) servis katmanindadir (SlackWebhookUrlPolicy);
 * burada yalniz bos/asiri uzun girdi elenir. {@code enabled} verilmezse {@code true}.
 */
public record SlackIntegrationRequest(
    @NotBlank @Size(max = 200) String webhookUrl, Boolean enabled) {}
