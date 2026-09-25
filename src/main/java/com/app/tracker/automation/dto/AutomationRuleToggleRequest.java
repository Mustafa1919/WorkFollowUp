package com.app.tracker.automation.dto;

import jakarta.validation.constraints.NotNull;

/**
 * {@code Boolean} (nullable) BILEREK kullanilir — {@code MeetingRequest.standupEnabled} hatasinin
 * (Dalga 2.3) AYNISI: primitif {@code boolean} eksik JSON alaninda Jackson constructor'inda 400
 * dondurur, bu da {@code @PreAuthorize}'DAN ONCE calisip auth testini maskeler.
 */
public record AutomationRuleToggleRequest(@NotNull Boolean enabled) {}
